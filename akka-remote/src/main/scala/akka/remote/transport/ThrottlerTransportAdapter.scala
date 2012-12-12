package akka.remote.transport

import ThrottlerTransportAdapter._
import akka.actor._
import akka.pattern.pipe
import akka.remote.transport.ActorTransportAdapter.AssociateUnderlying
import akka.remote.transport.ActorTransportAdapter.ListenUnderlying
import akka.remote.transport.ActorTransportAdapter.ListenerRegistered
import akka.remote.transport.AkkaPduCodec.Associate
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload, HandleEventListener }
import akka.remote.transport.ThrottledAssociation._
import akka.remote.transport.ThrottlerManager.Checkin
import akka.remote.transport.ThrottlerTransportAdapter.SetThrottle
import akka.remote.transport.Transport._
import akka.util.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.Promise
import scala.math.min
import scala.util.Success
import scala.util.control.NonFatal
import scala.concurrent.duration._

class ThrottlerProvider extends TransportAdapterProvider {

  def apply(wrappedTransport: Transport, system: ExtendedActorSystem): Transport =
    new ThrottlerTransportAdapter(wrappedTransport, system)

}

object ThrottlerTransportAdapter {
  val SchemeIdentifier = "trttl"
  val UniqueId = new java.util.concurrent.atomic.AtomicInteger(0)

  sealed trait Direction {
    def includes(other: Direction): Boolean
  }

  object Direction {
    case object Send extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Send ⇒ true
        case _    ⇒ false
      }
    }
    case object Receive extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Receive ⇒ true
        case _       ⇒ false
      }
    }
    case object Both extends Direction {
      override def includes(other: Direction): Boolean = true
    }
  }

  case class SetThrottle(address: Address, direction: Direction, mode: ThrottleMode)

  sealed trait ThrottleMode {
    def tryConsumeTokens(timeOfSend: Long, tokens: Int): (ThrottleMode, Boolean)
    def timeToAvailable(currentTime: Long, tokens: Int): Long
  }

  case class TokenBucket(capacity: Int, tokensPerSecond: Double, lastSend: Long, availableTokens: Int)
    extends ThrottleMode {

    private def isAvailable(timeOfSend: Long, tokens: Int): Boolean = if ((tokens > capacity && availableTokens > 0)) {
      true // Allow messages larger than capacity through, it will be recorded as negative tokens
    } else min((availableTokens + tokensGenerated(timeOfSend)), capacity) >= tokens

    override def tryConsumeTokens(timeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = {
      if (isAvailable(timeOfSend, tokens))
        (this.copy(
          lastSend = timeOfSend,
          availableTokens = min(availableTokens - tokens + tokensGenerated(timeOfSend), capacity)), true)
      else (this, false)
    }

    override def timeToAvailable(currentTime: Long, tokens: Int): Long = {
      val needed = (if (tokens > capacity) 1 else tokens) - tokensGenerated(currentTime)
      TimeUnit.SECONDS.toNanos((needed / tokensPerSecond).toLong)
    }

    private def tokensGenerated(timeOfSend: Long): Int =
      (TimeUnit.NANOSECONDS.toMillis(timeOfSend - lastSend) * tokensPerSecond / 1000.0).toInt
  }

  case object Unthrottled extends ThrottleMode {

    override def tryConsumeTokens(timeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = (this, true)
    override def timeToAvailable(currentTime: Long, tokens: Int): Long = 1L
  }

  case object Blackhole extends ThrottleMode {
    override def tryConsumeTokens(timeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = (this, false)
    override def timeToAvailable(currentTime: Long, tokens: Int): Long = 0L
  }
}

class ThrottlerTransportAdapter(_wrappedTransport: Transport, _system: ExtendedActorSystem)
  extends ActorTransportAdapter(_wrappedTransport, _system) {

  override protected def addedSchemeIdentifier = SchemeIdentifier
  override protected def maximumOverhead = 0
  protected def managerName = s"throttlermanager.${wrappedTransport.schemeIdentifier}${UniqueId.getAndIncrement}"
  protected def managerProps = Props(new ThrottlerManager(wrappedTransport))

  override def managementCommand(cmd: Any, statusPromise: Promise[Boolean]): Unit = cmd match {
    case s @ SetThrottle(_, _, _) ⇒
      manager ! s
      statusPromise.success(true)
    case _ ⇒ wrappedTransport.managementCommand(cmd, statusPromise)
  }
}

private[transport] object ThrottlerManager {
  case class OriginResolved()
  case class Checkin(origin: Address, handle: ThrottlerHandle)
}

private[transport] class ThrottlerManager(wrappedTransport: Transport) extends Actor {

  import context.dispatcher

  private val ids = Iterator from 0
  private var associationListener: AssociationEventListener = _
  private var throttlingModes = Map[Address, (ThrottleMode, Direction)]()
  private var handleTable = List[(Address, ThrottlerHandle)]()

  private def nakedAddress(address: Address): Address = address.copy(protocol = "", system = "")

  override def postStop(): Unit = wrappedTransport.shutdown()

  def receive: Receive = {
    case ListenUnderlying(listenAddress, upstreamListenerFuture) ⇒
      upstreamListenerFuture.future.map { ListenerRegistered(_) } pipeTo self

    case ListenerRegistered(listener) ⇒
      associationListener = listener
      context.become(ready)

    // Block inbound associations until handler is registered
    case InboundAssociation(handle) ⇒
      handle.disassociate()
  }

  private def ready: Receive = {
    case InboundAssociation(handle) ⇒
      val wrappedHandle = wrapHandle(handle, associationListener, inbound = true)
      wrappedHandle.throttlerActor ! wrappedHandle
    case AssociateUnderlying(remoteAddress, statusPromise) ⇒
      wrappedTransport.associate(remoteAddress).onComplete {
        case Success(Ready(handle)) ⇒
          val wrappedHandle = wrapHandle(handle, associationListener, inbound = false)
          val inMode = getInboundMode(nakedAddress(remoteAddress))
          wrappedHandle.outboundThrottleMode.set(getOutboundMode(nakedAddress(remoteAddress)))
          wrappedHandle.readHandlerPromise.future.map { (_, inMode) } pipeTo wrappedHandle.throttlerActor
          handleTable ::= nakedAddress(remoteAddress) -> wrappedHandle
          statusPromise.success(Ready(wrappedHandle))
        case s @ _ ⇒ statusPromise.complete(s)
      }
    case s @ SetThrottle(address, direction, mode) ⇒
      val naked = nakedAddress(address)
      throttlingModes += naked -> (mode, direction)
      handleTable.foreach {
        case (addr, handle) ⇒
          if (addr == naked) setMode(handle, mode, direction)
      }
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

  private def setMode(nakedAddress: Address, handle: ThrottlerHandle): Unit = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) ⇒ setMode(handle, mode, direction)
      case None                    ⇒ setMode(handle, Unthrottled, Direction.Both)
    }
  }

  private def setMode(handle: ThrottlerHandle, mode: ThrottleMode, direction: Direction): Unit = {
    if (direction.includes(Direction.Receive)) handle.throttlerActor ! mode
    if (direction.includes(Direction.Send)) handle.outboundThrottleMode.set(mode)
  }

  private def wrapHandle(originalHandle: AssociationHandle, listener: AssociationEventListener, inbound: Boolean): ThrottlerHandle = {
    val managerRef = self
    val throttlerActor = context.actorOf(Props(new ThrottledAssociation(managerRef, listener, originalHandle, inbound)),
      "throttler" + ids.next())
    ThrottlerHandle(originalHandle, throttlerActor)
  }

}

object ThrottledAssociation {
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

private[transport] class ThrottledAssociation(
  val manager: ActorRef,
  val associationHandler: AssociationEventListener,
  val originalHandle: AssociationHandle,
  val inbound: Boolean)
  extends Actor with LoggingFSM[ThrottlerState, ThrottlerData] {
  import context.dispatcher

  var inboundThrottleMode: ThrottleMode = _
  var queue = Queue.empty[ByteString]
  var upstreamListener: HandleEventListener = _

  override def postStop(): Unit = originalHandle.disassociate()

  if (inbound) startWith(WaitExposedHandle, Uninitialized) else {
    originalHandle.readHandlerPromise.success(self)
    startWith(WaitModeAndUpstreamListener, Uninitialized)
  }

  when(WaitExposedHandle) {
    case Event(handle: ThrottlerHandle, Uninitialized) ⇒
      // register to downstream layer and wait for origin
      originalHandle.readHandlerPromise.success(self)
      goto(WaitOrigin) using ExposedHandle(handle)
  }

  when(WaitOrigin) {
    case Event(InboundPayload(p), ExposedHandle(exposedHandle)) ⇒
      queue = queue enqueue p
      peekOrigin(p) match {
        case Some(origin) ⇒
          manager ! Checkin(origin, exposedHandle)
          goto(WaitMode)
        case None ⇒ stay()
      }
  }

  when(WaitMode) {
    case Event(InboundPayload(p), _) ⇒
      queue = queue enqueue p
      stay()
    case Event(mode: ThrottleMode, ExposedHandle(exposedHandle)) ⇒
      inboundThrottleMode = mode
      if (inboundThrottleMode == Blackhole) {
        queue = Queue.empty[ByteString]
        exposedHandle.disassociate()
        stop()
      } else {
        associationHandler notify InboundAssociation(exposedHandle)
        exposedHandle.readHandlerPromise.future pipeTo self
        goto(WaitUpstreamListener)
      }
  }

  when(WaitUpstreamListener) {
    case Event(InboundPayload(p), _) ⇒
      queue = queue enqueue p
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
      queue = queue enqueue p
      stay()
  }

  when(Throttling) {
    case Event(mode: ThrottleMode, _) ⇒
      inboundThrottleMode = mode
      if (inboundThrottleMode == Blackhole) queue = Queue.empty[ByteString]
      stay()
    case Event(InboundPayload(p), _) ⇒
      forwardOrDelay(p)
      stay()

    case Event(Dequeue, _) ⇒
      if (!queue.isEmpty) {
        val (payload, newqueue) = queue.dequeue
        upstreamListener notify InboundPayload(payload)
        queue = newqueue
        inboundThrottleMode = inboundThrottleMode.tryConsumeTokens(System.nanoTime(), payload.length)._1
        if (inboundThrottleMode == Unthrottled && !queue.isEmpty) self ! Dequeue
        else if (!queue.isEmpty) {
          context.system.scheduler.scheduleOnce(
            inboundThrottleMode.timeToAvailable(System.nanoTime(), queue.head.length) nanoseconds, self, Dequeue)
        }
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
      if (queue.isEmpty) {
        val tokens = payload.length
        val (newbucket, success) = inboundThrottleMode.tryConsumeTokens(System.nanoTime(), tokens)
        if (success) {
          inboundThrottleMode = newbucket
          upstreamListener notify InboundPayload(payload)
        } else {
          queue = queue.enqueue(payload)

          context.system.scheduler.scheduleOnce(
            inboundThrottleMode.timeToAvailable(System.nanoTime(), tokens) nanoseconds, self, Dequeue)
        }
      } else {
        queue = queue.enqueue(payload)
      }
    }
  }

}

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