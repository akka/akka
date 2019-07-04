/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport

import akka.actor._
import akka.pattern.{ ask, pipe, PromiseActorRef }
import akka.remote.transport.ActorTransportAdapter.AssociateUnderlying
import akka.remote.transport.AkkaPduCodec.Associate
import akka.remote.transport.AssociationHandle.{
  ActorHandleEventListener,
  DisassociateInfo,
  Disassociated,
  HandleEventListener,
  InboundPayload
}
import akka.remote.transport.ThrottlerManager.{ Checkin, Handle, Listener, ListenerAndMode }
import akka.remote.transport.ThrottlerTransportAdapter._
import akka.remote.transport.Transport._
import akka.util.{ ByteString, Timeout }
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._
import scala.math.min
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal
import akka.dispatch.sysmsg.{ Unwatch, Watch }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.remote.RARP
import com.github.ghik.silencer.silent

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
        case Send => true
        case _    => false
      }

      /**
       * Java API: get the singleton instance
       */
      def getInstance = this
    }

    @SerialVersionUID(1L)
    case object Receive extends Direction {
      override def includes(other: Direction): Boolean = other match {
        case Receive => true
        case _       => false
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
  final case class SetThrottle(address: Address, direction: Direction, mode: ThrottleMode)

  @SerialVersionUID(1L)
  case object SetThrottleAck {

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  sealed trait ThrottleMode extends NoSerializationVerificationNeeded {
    def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean)
    def timeToAvailable(currentNanoTime: Long, tokens: Int): FiniteDuration
  }

  @SerialVersionUID(1L)
  final case class TokenBucket(capacity: Int, tokensPerSecond: Double, nanoTimeOfLastSend: Long, availableTokens: Int)
      extends ThrottleMode {

    private def isAvailable(nanoTimeOfSend: Long, tokens: Int): Boolean =
      if ((tokens > capacity && availableTokens > 0)) {
        true // Allow messages larger than capacity through, it will be recorded as negative tokens
      } else min((availableTokens + tokensGenerated(nanoTimeOfSend)), capacity) >= tokens

    override def tryConsumeTokens(nanoTimeOfSend: Long, tokens: Int): (ThrottleMode, Boolean) = {
      if (isAvailable(nanoTimeOfSend, tokens))
        (
          this.copy(
            nanoTimeOfLastSend = nanoTimeOfSend,
            availableTokens = min(availableTokens - tokens + tokensGenerated(nanoTimeOfSend), capacity)),
          true)
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

  /**
   * Management Command to force disassociation of an address.
   */
  @SerialVersionUID(1L)
  final case class ForceDisassociate(address: Address)

  /**
   * Management Command to force disassociation of an address with an explicit error.
   */
  @SerialVersionUID(1L)
  final case class ForceDisassociateExplicitly(address: Address, reason: DisassociateInfo)

  @SerialVersionUID(1L)
  case object ForceDisassociateAck {

    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  /**
   * Java API: get the Direction.Send instance
   */
  def sendDirection(): Direction = Direction.Send

  /**
   * Java API: get the Direction.Receive instance
   */
  def receiveDirection(): Direction = Direction.Receive

  /**
   * Java API: get the Direction.Both instance
   */
  def bothDirection(): Direction = Direction.Both

  /**
   * Java API: get the ThrottleMode.Blackhole instance
   */
  def blackholeThrottleMode(): ThrottleMode = Blackhole

  /**
   * Java API: get the ThrottleMode.Unthrottled instance
   */
  def unthrottledThrottleMode(): ThrottleMode = Unthrottled
}

class ThrottlerTransportAdapter(_wrappedTransport: Transport, _system: ExtendedActorSystem)
    extends ActorTransportAdapter(_wrappedTransport, _system) {

  override protected def addedSchemeIdentifier = SchemeIdentifier
  override protected def maximumOverhead = 0
  protected def managerName: String =
    s"throttlermanager.${wrappedTransport.schemeIdentifier}${UniqueId.getAndIncrement}"
  protected def managerProps: Props = {
    val wt = wrappedTransport
    Props(classOf[ThrottlerManager], wt)
  }

  override def managementCommand(cmd: Any): Future[Boolean] = {
    import ActorTransportAdapter.AskTimeout
    cmd match {
      case s: SetThrottle                 => (manager ? s).map { case SetThrottleAck => true }
      case f: ForceDisassociate           => (manager ? f).map { case ForceDisassociateAck => true }
      case f: ForceDisassociateExplicitly => (manager ? f).map { case ForceDisassociateAck => true }
      case _                              => wrappedTransport.managementCommand(cmd)
    }
  }
}

/**
 * INTERNAL API
 */
private[transport] object ThrottlerManager {
  final case class Checkin(origin: Address, handle: ThrottlerHandle) extends NoSerializationVerificationNeeded

  final case class AssociateResult(handle: AssociationHandle, statusPromise: Promise[AssociationHandle])
      extends NoSerializationVerificationNeeded

  final case class ListenerAndMode(listener: HandleEventListener, mode: ThrottleMode)
      extends NoSerializationVerificationNeeded

  final case class Handle(handle: ThrottlerHandle) extends NoSerializationVerificationNeeded

  final case class Listener(listener: HandleEventListener) extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API
 */
private[transport] class ThrottlerManager(wrappedTransport: Transport)
    extends ActorTransportAdapterManager
    with ActorLogging {

  import ThrottlerManager._
  import context.dispatcher

  private var throttlingModes = Map[Address, (ThrottleMode, Direction)]()
  private var handleTable = List[(Address, ThrottlerHandle)]()

  private def nakedAddress(address: Address): Address = address.copy(protocol = "", system = "")

  override def ready: Receive = {
    case InboundAssociation(handle) =>
      val wrappedHandle = wrapHandle(handle, associationListener, inbound = true)
      wrappedHandle.throttlerActor ! Handle(wrappedHandle)
    case AssociateUnderlying(remoteAddress, statusPromise) =>
      wrappedTransport.associate(remoteAddress).onComplete {
        // Slight modification of pipe, only success is sent, failure is propagated to a separate future
        case Success(handle) => self ! AssociateResult(handle, statusPromise)
        case Failure(e)      => statusPromise.failure(e)
      }
    // Finished outbound association and got back the handle
    case AssociateResult(handle, statusPromise) =>
      val wrappedHandle = wrapHandle(handle, associationListener, inbound = false)
      val naked = nakedAddress(handle.remoteAddress)
      val inMode = getInboundMode(naked)
      wrappedHandle.outboundThrottleMode.set(getOutboundMode(naked))
      wrappedHandle.readHandlerPromise.future.map { ListenerAndMode(_, inMode) }.pipeTo(wrappedHandle.throttlerActor)
      handleTable ::= naked -> wrappedHandle
      statusPromise.success(wrappedHandle)
    case SetThrottle(address, direction, mode) =>
      val naked = nakedAddress(address)
      throttlingModes = throttlingModes.updated(naked, (mode, direction))
      val ok = Future.successful(SetThrottleAck)
      Future
        .sequence(handleTable.map {
          case (`naked`, handle) => setMode(handle, mode, direction)
          case _                 => ok
        })
        .map(_ => SetThrottleAck)
        .pipeTo(sender())
    case ForceDisassociate(address) =>
      val naked = nakedAddress(address)
      handleTable.foreach {
        case (`naked`, handle) => handle.disassociate(s"the disassociation was forced by ${sender()}", log)
        case _                 =>
      }
      sender() ! ForceDisassociateAck
    case ForceDisassociateExplicitly(address, reason) =>
      val naked = nakedAddress(address)
      handleTable.foreach {
        case (`naked`, handle) => handle.disassociateWithFailure(reason)
        case _                 =>
      }
      sender() ! ForceDisassociateAck

    case Checkin(origin, handle) =>
      val naked: Address = nakedAddress(origin)
      handleTable ::= naked -> handle
      setMode(naked, handle)

  }

  private def getInboundMode(nakedAddress: Address): ThrottleMode = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) if direction.includes(Direction.Receive) => mode
      case _                                                                => Unthrottled
    }
  }

  private def getOutboundMode(nakedAddress: Address): ThrottleMode = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) if direction.includes(Direction.Send) => mode
      case _                                                             => Unthrottled
    }
  }

  private def setMode(nakedAddress: Address, handle: ThrottlerHandle): Future[SetThrottleAck.type] = {
    throttlingModes.get(nakedAddress) match {
      case Some((mode, direction)) => setMode(handle, mode, direction)
      case None                    => setMode(handle, Unthrottled, Direction.Both)
    }
  }

  private def setMode(
      handle: ThrottlerHandle,
      mode: ThrottleMode,
      direction: Direction): Future[SetThrottleAck.type] = {
    if (direction.includes(Direction.Send))
      handle.outboundThrottleMode.set(mode)
    if (direction.includes(Direction.Receive))
      askModeWithDeathCompletion(handle.throttlerActor, mode)(ActorTransportAdapter.AskTimeout)
    else
      Future.successful(SetThrottleAck)
  }

  // silent because of use of isTerminated
  @silent
  private def askModeWithDeathCompletion(target: ActorRef, mode: ThrottleMode)(
      implicit timeout: Timeout): Future[SetThrottleAck.type] = {
    if (target.isTerminated) Future.successful(SetThrottleAck)
    else {
      val internalTarget = target.asInstanceOf[InternalActorRef]
      val ref = PromiseActorRef(internalTarget.provider, timeout, target, mode.getClass.getName)
      internalTarget.sendSystemMessage(Watch(internalTarget, ref))
      target.tell(mode, ref)
      ref.result.future.transform({
        case Terminated(t) if t.path == target.path => SetThrottleAck
        case SetThrottleAck                         => { internalTarget.sendSystemMessage(Unwatch(target, ref)); SetThrottleAck }
      }, t => { internalTarget.sendSystemMessage(Unwatch(target, ref)); t })(ref.internalCallingThreadExecutionContext)
    }
  }

  private def wrapHandle(
      originalHandle: AssociationHandle,
      listener: AssociationEventListener,
      inbound: Boolean): ThrottlerHandle = {
    val managerRef = self
    ThrottlerHandle(
      originalHandle,
      context.actorOf(
        RARP(context.system)
          .configureDispatcher(Props(classOf[ThrottledAssociation], managerRef, listener, originalHandle, inbound))
          .withDeploy(Deploy.local),
        "throttler" + nextId()))
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
  final case class ExposedHandle(handle: ThrottlerHandle) extends ThrottlerData

  final case class FailWith(reason: DisassociateInfo)
}

/**
 * INTERNAL API
 */
private[transport] class ThrottledAssociation(
    val manager: ActorRef,
    val associationHandler: AssociationEventListener,
    val originalHandle: AssociationHandle,
    val inbound: Boolean)
    extends Actor
    with LoggingFSM[ThrottledAssociation.ThrottlerState, ThrottledAssociation.ThrottlerData]
    with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import ThrottledAssociation._
  import context.dispatcher

  var inboundThrottleMode: ThrottleMode = _
  var throttledMessages = Queue.empty[ByteString]
  var upstreamListener: HandleEventListener = _

  override def postStop(): Unit = originalHandle.disassociate("the owning ThrottledAssociation stopped", log)

  if (inbound) startWith(WaitExposedHandle, Uninitialized)
  else {
    originalHandle.readHandlerPromise.success(ActorHandleEventListener(self))
    startWith(WaitModeAndUpstreamListener, Uninitialized)
  }

  when(WaitExposedHandle) {
    case Event(Handle(handle), Uninitialized) =>
      // register to downstream layer and wait for origin
      originalHandle.readHandlerPromise.success(ActorHandleEventListener(self))
      goto(WaitOrigin).using(ExposedHandle(handle))
  }

  when(WaitOrigin) {
    case Event(InboundPayload(p), ExposedHandle(exposedHandle)) =>
      throttledMessages = throttledMessages.enqueue(p)
      peekOrigin(p) match {
        case Some(origin) =>
          manager ! Checkin(origin, exposedHandle)
          goto(WaitMode)
        case None => stay()
      }
  }

  when(WaitMode) {
    case Event(InboundPayload(p), _) =>
      throttledMessages = throttledMessages.enqueue(p)
      stay()
    case Event(mode: ThrottleMode, ExposedHandle(exposedHandle)) =>
      inboundThrottleMode = mode
      try if (mode == Blackhole) {
        throttledMessages = Queue.empty[ByteString]
        exposedHandle.disassociate("the association was blackholed", log)
        stop()
      } else {
        associationHandler.notify(InboundAssociation(exposedHandle))
        exposedHandle.readHandlerPromise.future.map(Listener(_)).pipeTo(self)
        goto(WaitUpstreamListener)
      } finally sender() ! SetThrottleAck
  }

  when(WaitUpstreamListener) {
    case Event(InboundPayload(p), _) =>
      throttledMessages = throttledMessages.enqueue(p)
      stay()
    case Event(Listener(listener), _) =>
      upstreamListener = listener
      self ! Dequeue
      goto(Throttling)
  }

  when(WaitModeAndUpstreamListener) {
    case Event(ListenerAndMode(listener: HandleEventListener, mode: ThrottleMode), _) =>
      upstreamListener = listener
      inboundThrottleMode = mode
      self ! Dequeue
      goto(Throttling)
    case Event(InboundPayload(p), _) =>
      throttledMessages = throttledMessages.enqueue(p)
      stay()
  }

  when(Throttling) {
    case Event(mode: ThrottleMode, _) =>
      inboundThrottleMode = mode
      if (mode == Blackhole) throttledMessages = Queue.empty[ByteString]
      cancelTimer(DequeueTimerName)
      if (throttledMessages.nonEmpty)
        scheduleDequeue(inboundThrottleMode.timeToAvailable(System.nanoTime(), throttledMessages.head.length))
      sender() ! SetThrottleAck
      stay()
    case Event(InboundPayload(p), _) =>
      forwardOrDelay(p)
      stay()

    case Event(Dequeue, _) =>
      if (throttledMessages.nonEmpty) {
        val (payload, newqueue) = throttledMessages.dequeue
        upstreamListener.notify(InboundPayload(payload))
        throttledMessages = newqueue
        inboundThrottleMode = inboundThrottleMode.tryConsumeTokens(System.nanoTime(), payload.length)._1
        if (throttledMessages.nonEmpty)
          scheduleDequeue(inboundThrottleMode.timeToAvailable(System.nanoTime(), throttledMessages.head.length))
      }
      stay()

  }

  whenUnhandled {
    // we should always set the throttling mode
    case Event(mode: ThrottleMode, _) =>
      inboundThrottleMode = mode
      sender() ! SetThrottleAck
      stay()
    case Event(Disassociated(_), _) =>
      stop() // not notifying the upstream handler is intentional: we are relying on heartbeating
    case Event(FailWith(reason), _) =>
      if (upstreamListener ne null) upstreamListener.notify(Disassociated(reason))
      stop()
  }

  // This method captures ASSOCIATE packets and extracts the origin address
  private def peekOrigin(b: ByteString): Option[Address] = {
    try {
      AkkaPduProtobufCodec.decodePdu(b) match {
        case Associate(info) => Some(info.origin)
        case _               => None
      }
    } catch {
      // This layer should not care about malformed packets. Also, this also useful for testing, because
      // arbitrary payload could be passed in
      case NonFatal(_) => None
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
          upstreamListener.notify(InboundPayload(payload))
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
    case Blackhole                   => // Do nothing
    case _ if delay <= Duration.Zero => self ! Dequeue
    case _                           => startSingleTimer(DequeueTimerName, Dequeue, delay)
  }

}

/**
 * INTERNAL API
 */
private[transport] final case class ThrottlerHandle(_wrappedHandle: AssociationHandle, throttlerActor: ActorRef)
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
      case Blackhole => true
      case bucket @ _ =>
        val success = tryConsume(outboundThrottleMode.get())
        if (success) wrappedHandle.write(payload) else false
      // FIXME: this depletes the token bucket even when no write happened!! See #2825
    }

  }

  override def disassociate(): Unit = throttlerActor ! PoisonPill

  def disassociateWithFailure(reason: DisassociateInfo): Unit = {
    throttlerActor ! ThrottledAssociation.FailWith(reason)
  }

}
