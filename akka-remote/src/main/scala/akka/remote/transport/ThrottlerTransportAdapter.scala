/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.transport

import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.remote.transport.ActorTransportAdapter.AssociateUnderlying
import akka.remote.transport.AkkaPduCodec.Associate
import akka.remote.transport.AssociationHandle.{ ActorHandleEventListener, Disassociated, InboundPayload, HandleEventListener }
import akka.remote.transport.ThrottlerManager.Checkin
import akka.remote.transport.ThrottlerTransportAdapter._
import akka.remote.transport.Transport._
import akka.util.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.math.min
import scala.util.{ Success, Failure }
import scala.util.control.NonFatal
import scala.concurrent.duration._

class ThrottlerProvider extends TransportAdapterProvider {

  override def create(wrappedTransport: Transport, system: ExtendedActorSystem): Transport =
    new ThrottlerTransportAdapter(wrappedTransport, system)

}

object ThrottlerTransportAdapter {
  val SchemeIdentifier = "trttl"
  val UniqueId = new java.util.concurrent.atomic.AtomicInteger(0)

  sealed trait Direction {
    def includes(other: Direction): Boolean
  }

  object Direction {

    @SerialVersionUID(1L)
    case object Send extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Send ⇒ true
        case _    ⇒ false
      }

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }

    @SerialVersionUID(1L)
    case object Receive extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Receive ⇒ true
        case _       ⇒ false
      }

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }

    @SerialVersionUID(1L)
    case object Both extends Direction {
      override def includes(other: Direction): Boolean = true

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }
  }

  @SerialVersionUID(1L)
  case class SetThrottle(address: Address, direction: Direction, mode: ThrottleMode)

  @SerialVersionUID(1L)
  case object SetThrottleAck {
    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  sealed trait ThrottleMode {
    def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean)
    def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration
  }

  @SerialVersionUID(1L)
  case class TokenBucket(capacity: Int, tokensPerSecond: Double, nanoTimeOfLastSend: Long, availableTokens: Int)
    extends ThrottleMode {

    private def isAvailable(nanoTimeOfSend: Long, tokens: Int): Boolean =
      if ((tokens > capacity && availableTokens > 0)) {
        true // Allow messages larger than capacity through, it will be recorded as negative tokens
      } else min((availableTokens + tokensGenerated(nanoTimeOfSend)), capacity) >= tokens

    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = {
      if (isAvailable(nanoTimeOfSend, tokens))
        (this.copy(
          nanoTimeOfLastSend = nanoTimeOfSend,
          availableTokens = min(availableTokens - tokens + tokensGenerated(nanoTimeOfSend), capacity)), true)
      else (this, false)
    }

    override def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration = {
      val needed = (if (tokens > capacity) 1 else tokens) - tokensGenerated(currentNanoTime)
      (needed / tokensPerSecond).seconds
    }

    private def tokensGenerated(nanoTimeOfSend: Long): Int =
      (TimeUnit.NANOSECONDS.toMillis(nanoTimeOfSend - nanoTimeOfLastSend) * tokensPerSecond / 1000.0).toInt
  }

  @SerialVersionUID(1L)
  case object Unthrottled extends ThrottleMode {
    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = (this, true)
    override def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration = Duration.Zero

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this

  }

  @SerialVersionUID(1L)
  case object Blackhole extends ThrottleMode {
    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = (this, false)
    override def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration = Duration.Zero

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }
}

class ThrottlerTransportAdapter(_wrappedTransport: Transport, _system: ExtendedActorSystem)
  extends ActorTransportAdapter(_wrappedTransport, _system) {

  override protected def addedSchemeIdentifier = SchemeIdentifier
  override protected def maximumOverhead = 0
  protected def managerName = s"throttlermanager.${wrappedTransport.schemeIdentifier}${UniqueId.getAndIncrement}"
  protected def managerProps = {
    val wt = wrappedTransport
    Props(new ThrottlerManager(wt))
  }

  override def managementCommand(cmd: Any): Future[Boolean] = cmd match {
    case s: SetThrottle ⇒
      import ActorTransportAdapter.AskTimeout
      manager ? s map { case SetThrottleAck ⇒ true }
    case _ ⇒ wrappedTransport.managementCommand(cmd)
  }
}

/**
 * INTERNAL API
 */
private[transport] object ThrottlerManager {
  case class OriginResolved()
  case class Checkin(origin: Address, handle: ThrottlerHandle)
}

/**
 * INTERNAL API
 */
private[transport] class ThrottlerManager(wrappedTransport: Transport) extends ActorTransportAdapterManager {

  import context.dispatcher

  private var throttlingModes = Map[Address, (ThrottleMode, Direction)]()
  private var handleTable = List[(Address, ThrottlerHandle)]()

  private def nakedAddress(address: Address): Address = address.copy(protocol = "", system = "")

  override def postStop(): Unit = wrappedTransport.shutdown()

  override def ready: Receive = {
    case InboundAssociation(handle) ⇒
      val wrappedHandle = wrapHandle(handle, associationListener, inbound = true)
      wrappedHandle.throttlerActor ! wrappedHandle
    case AssociateUnderlying(remoteAddress, statusPromise) ⇒
      wrappedTransport.associate(remoteAddress) onComplete {
        // Slight modification of pipe, only success is sent, failure is propagated to a separate future
        case Success(handle) ⇒ self ! (handle, statusPromise)
        case Failure(e)      ⇒ statusPromise.failure(e)
      }
    // Finished outbound association and got back the handle
    case (handle: AssociationHandle, statusPromise: Promise[AssociationHandle]) ⇒
      val wrappedHandle = wrapHandle(handle, associationListener, inbound = false)
      val naked = nakedAddress(handle.remoteAddress)
      val inMode = getInboundMode(naked)
      wrappedHandle.outboundThrottleMode.set(getOutboundMode(naked))
      wrappedHandle.readHandlerPromise.future.map { (_, inMode) } pipeTo wrappedHandle.throttlerActor
      handleTable ::= naked -> wrappedHandle
      statusPromise.success(wrappedHandle)
    case SetThrottle(address, direction, mode) ⇒
      val naked = nakedAddress(address)
      throttlingModes += naked -> (mode, direction)
      val ok = Future.successful(SetThrottleAck)
      val allAcks = handleTable.map {
        case (`naked`, handle) ⇒ setMode(handle, mode, direction)
        case _                 ⇒ ok
      }

      Future.sequence(allAcks).map(_ ⇒ SetThrottleAck) pipeTo sender

    case Checkin(origin, handle) ⇒
      val naked: Address = nakedAddress(origin)
      handleTable ::= naked -> handle
      setMode(naked, handle)

  }

  private def getInboundMode(nakedAddress: Address): ThrottleMode = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) if direction.includes(Direction.Receive) ⇒ mode
      case _ ⇒ Unthrottled
    }
  }

  private def getOutboundMode(nakedAddress: Address): ThrottleMode = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) if direction.includes(Direction.Send) ⇒ mode
      case _ ⇒ Unthrottled
    }
  }

  private def setMode(nakedAddress: Address, handle: ThrottlerHandle): Future[SetThrottleAck.type] = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) ⇒ setMode(handle, mode, direction)
      case None                    ⇒ setMode(handle, Unthrottled, Direction.Both)
    }
  }

  private def setMode(handle: ThrottlerHandle, mode: ThrottleMode, direction: Direction): Future[SetThrottleAck.type] = {
    import ActorTransportAdapter.AskTimeout
    if (direction.includes(Direction.Send))
      handle.outboundThrottleMode.set(mode)
    if (direction.includes(Direction.Receive))
      (handle.throttlerActor ? mode).mapTo[SetThrottleAck.type]
    else
      Future.successful(SetThrottleAck)
  }

  private def wrapHandle(originalHandle: AssociationHandle, listener: AssociationEventListener, inbound: Boolean): ThrottlerHandle = {
    val managerRef = self
    val throttlerActor = context.actorOf(Props(new ThrottledAssociation(managerRef, listener, originalHandle, inbound)),
      "throttler" + nextId())
    ThrottlerHandle(originalHandle, throttlerActor)
  }

}

/**
 * INTERNAL API
 */
private[transport] object ThrottledAssociation {
  private final val DequeueTimerName = "dequeue"

  case object Dequeue

  sealed trait ThrottlerState

  // --- Chain of states for inbound associations

  // Waiting for the ThrottlerHandle coupled with the throttler actor.
  case object WaitExposedHandle extends ThrottlerState
  // Waiting for the ASSOCIATE message that contains the origin address of the remote endpoint
  case object WaitOrigin extends ThrottlerState
  // After origin is known and a Checkin message is sent to the manager, we must wait for the ThrottlingMode for the
  // address
  case object WaitMode extends ThrottlerState
  // After all information is known, the throttler must wait for the upstream listener to be able to forward messages
  case object WaitUpstreamListener extends ThrottlerState

  // --- States for outbound associations

  // Waiting for the tuple containing the upstream listener and ThrottleMode
  case object WaitModeAndUpstreamListener extends ThrottlerState

  // Fully initialized state
  case object Throttling extends ThrottlerState

  sealed trait ThrottlerData
  case object Uninitialized extends ThrottlerData
  case class ExposedHandle(handle: ThrottlerHandle) extends ThrottlerData
}

/**
 * INTERNAL API
 */
private[transport] class ThrottledAssociation(
  val manager: ActorRef,
  val associationHandler: AssociationEventListener,
  val originalHandle: AssociationHandle,
  val inbound: Boolean)
  extends Actor with LoggingFSM[ThrottledAssociation.ThrottlerState, ThrottledAssociation.ThrottlerData] {
  import ThrottledAssociation._
  import context.dispatcher

  var inboundThrottleMode: ThrottleMode = _
  var throttledMessages = Queue.empty[ByteString]
  var upstreamListener: HandleEventListener = _

  override def postStop(): Unit = originalHandle.disassociate()

  if (inbound) startWith(WaitExposedHandle, Uninitialized) else {
    originalHandle.readHandlerPromise.success(ActorHandleEventListener(self))
    startWith(WaitModeAndUpstreamListener, Uninitialized)
  }

  when(WaitExposedHandle) {
    case Event(handle: ThrottlerHandle, Uninitialized) ⇒
      // register to downstream layer and wait for origin
      originalHandle.readHandlerPromise.success(ActorHandleEventListener(self))
      goto(WaitOrigin) using ExposedHandle(handle)
  }

  when(WaitOrigin) {
    case Event(InboundPayload(p), ExposedHandle(exposedHandle)) ⇒
      throttledMessages = throttledMessages enqueue p
      peekOrigin(p) match {
        case Some(origin) ⇒
          manager ! Checkin(origin, exposedHandle)
          goto(WaitMode)
        case None ⇒ stay()
      }
  }

  when(WaitMode) {
    case Event(InboundPayload(p), _) ⇒
      throttledMessages = throttledMessages enqueue p
      stay()
    case Event(mode: ThrottleMode, ExposedHandle(exposedHandle)) ⇒
      inboundThrottleMode = mode
      try if (mode == Blackhole) {
        throttledMessages = Queue.empty[ByteString]
        exposedHandle.disassociate()
        stop()
      } else {
        associationHandler notify InboundAssociation(exposedHandle)
        exposedHandle.readHandlerPromise.future pipeTo self
        goto(WaitUpstreamListener)
      } finally sender ! SetThrottleAck
  }

  when(WaitUpstreamListener) {
    case Event(InboundPayload(p), _) ⇒
      throttledMessages = throttledMessages enqueue p
      stay()
    case Event(listener: HandleEventListener, _) ⇒
      upstreamListener = listener
      self ! Dequeue
      goto(Throttling)
  }

  when(WaitModeAndUpstreamListener) {
    case Event((listener: HandleEventListener, mode: ThrottleMode), _) ⇒
      upstreamListener = listener
      inboundThrottleMode = mode
      self ! Dequeue
      goto(Throttling)
    case Event(InboundPayload(p), _) ⇒
      throttledMessages = throttledMessages enqueue p
      stay()
  }

  when(Throttling) {
    case Event(mode: ThrottleMode, _) ⇒
      inboundThrottleMode = mode
      if (mode == Blackhole) throttledMessages = Queue.empty[ByteString]
      cancelTimer(DequeueTimerName)
      if (throttledMessages.nonEmpty)
        scheduleDequeue(inboundThrottleMode.timeToAvailable(System.nanoTime(), throttledMessages.head.length))
      sender ! SetThrottleAck
      stay()
    case Event(InboundPayload(p), _) ⇒
      forwardOrDelay(p)
      stay()

    case Event(Dequeue, _) ⇒
      if (throttledMessages.nonEmpty) {
        val (payload, newqueue) = throttledMessages.dequeue
        upstreamListener notify InboundPayload(payload)
        throttledMessages = newqueue
        inboundThrottleMode = inboundThrottleMode.tryConsumeTokens(System.nanoTime(), payload.length)._1
        if (throttledMessages.nonEmpty)
          scheduleDequeue(inboundThrottleMode.timeToAvailable(System.nanoTime(), throttledMessages.head.length))
      }
      stay()

  }

  whenUnhandled {
    case Event(Disassociated, _) ⇒
      if (upstreamListener ne null) upstreamListener notify Disassociated
      originalHandle.disassociate()
      stop()

  }

  // This method captures ASSOCIATE packets and extracts the origin address
  private def peekOrigin(b: ByteString): Option[Address] = {
    try {
      AkkaPduProtobufCodec.decodePdu(b) match {
        case Associate(_, origin) ⇒ Some(origin)
        case _                    ⇒ None
      }
    } catch {
      // This layer should not care about malformed packets. Also, this also useful for testing, because
      // arbitrary payload could be passed in
      case NonFatal(e) ⇒ None
    }
  }

  def forwardOrDelay(payload: ByteString): Unit = {
    if (inboundThrottleMode == Blackhole) {
      // Do nothing
    } else {
      if (throttledMessages.isEmpty) {
        val tokens = payload.length
        val (newbucket, success) = inboundThrottleMode.tryConsumeTokens(System.nanoTime(), tokens)
        if (success) {
          inboundThrottleMode = newbucket
          upstreamListener notify InboundPayload(payload)
        } else {
          throttledMessages = throttledMessages.enqueue(payload)
          scheduleDequeue(inboundThrottleMode.timeToAvailable(System.nanoTime(), tokens))
        }
      } else {
        throttledMessages = throttledMessages.enqueue(payload)
      }
    }
  }

  def scheduleDequeue(delay: FiniteDuration): Unit = inboundThrottleMode match {
    case Blackhole                   ⇒ // Do nothing
    case _ if delay <= Duration.Zero ⇒ self ! Dequeue
    case _                           ⇒ setTimer(DequeueTimerName, Dequeue, delay, repeat = false)
  }

}

/**
 * INTERNAL API
 */
private[transport] case class ThrottlerHandle(_wrappedHandle: AssociationHandle, throttlerActor: ActorRef)
  extends AbstractTransportAdapterHandle(_wrappedHandle, SchemeIdentifier) {

  private[transport] val outboundThrottleMode = new AtomicReference[ThrottleMode](Unthrottled)

  override val readHandlerPromise: Promise[HandleEventListener] = Promise()

  override def write(payload: ByteString): Boolean = {
    val tokens = payload.length

    @tailrec def tryConsume(currentBucket: ThrottleMode): Boolean = {
      val timeOfSend = System.nanoTime()
      val (newBucket, allow) = currentBucket.tryConsumeTokens(timeOfSend, tokens)
      if (allow) {
        if (outboundThrottleMode.compareAndSet(currentBucket, newBucket)) true
        else tryConsume(outboundThrottleMode.get())
      } else false
    }

    outboundThrottleMode.get match {
      case Blackhole ⇒ true
      case bucket @ _ ⇒
        val success = tryConsume(outboundThrottleMode.get())
        if (success) wrappedHandle.write(payload)
        success
    }

  }

  override def disassociate(): Unit = {
    throttlerActor ! PoisonPill
  }

}
