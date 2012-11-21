package akka.remote.transport

import akka.AkkaException
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.pattern.pipe
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AkkaProtocolTransport._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.ProtocolStateActor._
import akka.remote.transport.Transport._
import akka.remote.{ PhiAccrualFailureDetector, FailureDetector, RemoteActorRefProvider }
import akka.util.ByteString
import com.typesafe.config.Config
import scala.concurrent.duration.{ Duration, FiniteDuration, MILLISECONDS }
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Success, Failure }
import java.net.URLEncoder
import scala.collection.immutable.Queue

class AkkaProtocolException(msg: String, cause: Throwable) extends AkkaException(msg, cause)

private[remote] class AkkaProtocolSettings(config: Config) {

  import config._

  val FailureDetectorThreshold: Double = getDouble("akka.remoting.failure-detector.threshold")

  val FailureDetectorMaxSampleSize: Int = getInt("akka.remoting.failure-detector.max-sample-size")

  val FailureDetectorStdDeviation: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.failure-detector.min-std-deviation"), MILLISECONDS)

  val AcceptableHeartBeatPause: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.failure-detector.acceptable-heartbeat-pause"), MILLISECONDS)

  val HeartBeatInterval: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.heartbeat-interval"), MILLISECONDS)

  val WaitActivityEnabled: Boolean = getBoolean("akka.remoting.wait-activity-enabled")

  val RequireCookie: Boolean = getBoolean("akka.remoting.require-cookie")

  val SecureCookie: String = getString("akka.remoting.secure-cookie")
}

private[remote] object AkkaProtocolTransport {
  val AkkaScheme: String = "akka"
  val AkkaOverhead: Int = 0 //Don't know yet
  val UniqueId = new java.util.concurrent.atomic.AtomicInteger(0)

  sealed trait TransportOperation
  case class HandlerRegistered(handler: ActorRef) extends TransportOperation
  case class AssociateUnderlying(remoteAddress: Address, statusPromise: Promise[Status]) extends TransportOperation
  case class ListenUnderlying(listenPromise: Promise[(Address, Promise[ActorRef])]) extends TransportOperation
  case object DisassociateUnderlying extends TransportOperation

  def augmentScheme(originalScheme: String): String = s"$originalScheme.$AkkaScheme"

  def augmentScheme(address: Address): Address = address.copy(protocol = augmentScheme(address.protocol))

  def removeScheme(scheme: String): String = if (scheme.endsWith(s".$AkkaScheme"))
    scheme.take(scheme.length - AkkaScheme.length - 1)
  else scheme

  def removeScheme(address: Address): Address = address.copy(protocol = removeScheme(address.protocol))
}

/**
 * Implementation of the Akka protocol as a Trasnsport that wraps an underlying Transport instance.
 *
 * Features provided by this transport are:
 *  - Soft-state associations via the use of heartbeats and failure detectors
 *  - Secure-cookie handling
 *  - Transparent origin address handling
 *  - Fire-And-Forget vs. implicit ack based handshake (controllable via wait-activity-enabled configuration option)
 *  - pluggable codecs to encode and decode Akka PDUs
 *
 * It is not possible to load this transport dynamically using the configuration of remoting, because it does not
 * expose a constructor with [[com.typesafe.config.Config]] and [[akka.actor.ExtendedActorSystem]] parameters.
 * This transport is instead loaded automatically by [[akka.remote.Remoting]] to wrap all the dynamically loaded
 * transports.
 *
 * @param wrappedTransport
 *   the underlying transport that will be used for communication
 * @param system
 *   the actor system
 * @param settings
 *   the configuration options of the Akka protocol
 * @param codec
 *   the codec that will be used to encode/decode Akka PDUs
 */
private[remote] class AkkaProtocolTransport(
  private val wrappedTransport: Transport,
  private val system: ActorSystem,
  private val settings: AkkaProtocolSettings,
  private val codec: AkkaPduCodec) extends Transport {

  override val schemeIdentifier: String = augmentScheme(wrappedTransport.schemeIdentifier)

  override def isResponsibleFor(address: Address): Boolean = wrappedTransport.isResponsibleFor(removeScheme(address))

  //TODO: make this the child of someone more appropriate
  private val manager = system.asInstanceOf[ActorSystemImpl].systemActorOf(
    Props(new AkkaProtocolManager(wrappedTransport, settings)),
    s"akkaprotocolmanager.${wrappedTransport.schemeIdentifier}${UniqueId.getAndIncrement}")

  override val maximumPayloadBytes: Int = wrappedTransport.maximumPayloadBytes - AkkaProtocolTransport.AkkaOverhead

  override def listen: Future[(Address, Promise[ActorRef])] = {
    // Prepare a future, and pass its promise to the manager
    val listenPromise: Promise[(Address, Promise[ActorRef])] = Promise()

    manager ! ListenUnderlying(listenPromise)

    listenPromise.future
  }

  override def associate(remoteAddress: akka.actor.Address): Future[Status] = {
    // Prepare a future, and pass its promise to the manager
    val statusPromise: Promise[Status] = Promise()

    manager ! AssociateUnderlying(remoteAddress, statusPromise)

    statusPromise.future
  }

  override def shutdown(): Unit = {
    manager ! PoisonPill
  }
}

private[transport] class AkkaProtocolManager(private val wrappedTransport: Transport,
                                             private val settings: AkkaProtocolSettings) extends Actor {

  import context.dispatcher

  // The AkkaProtocolTransport does not handle the recovery of associations, this task is implemented in the
  // remoting itself. Hence the strategy Stop.
  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(_) ⇒ Stop
  }

  private var nextId = 0L

  private val associationHandlerPromise: Promise[ActorRef] = Promise()
  associationHandlerPromise.future.map { HandlerRegistered(_) } pipeTo self

  @volatile var localAddress: Address = _

  private var associationHandler: ActorRef = _

  def receive: Receive = {
    case ListenUnderlying(listenPromise) ⇒
      val listenFuture = wrappedTransport.listen

      // - Receive the address and promise from original transport
      // - then register ourselves as listeners
      // - then complete the exposed promise with the modified contents
      listenFuture.onComplete {
        case Success((address, wrappedTransportHandlerPromise)) ⇒
          // Register ourselves as the handler for the wrapped transport's listen call
          wrappedTransportHandlerPromise.success(self)
          localAddress = address
          // Pipe the result to the original caller
          listenPromise.success((augmentScheme(address), associationHandlerPromise))
        case Failure(reason) ⇒ listenPromise.failure(reason)
      }

    case HandlerRegistered(handler) ⇒
      associationHandler = handler
      context.become(ready)

    // Block inbound associations until handler is registered
    case InboundAssociation(handle) ⇒ handle.disassociate()
  }

  private def actorNameFor(remoteAddress: Address): String = {
    nextId += 1
    "akkaProtocol-" + URLEncoder.encode(remoteAddress.toString, "utf-8") + "-" + nextId
  }

  private def ready: Receive = {
    case InboundAssociation(handle) ⇒
      context.actorOf(Props(new ProtocolStateActor(
        localAddress,
        handle,
        associationHandler,
        settings,
        AkkaPduProtobufCodec,
        createFailureDetector())), actorNameFor(handle.remoteAddress))

    case AssociateUnderlying(remoteAddress, statusPromise) ⇒
      context.actorOf(Props(new ProtocolStateActor(
        localAddress,
        remoteAddress,
        statusPromise,
        wrappedTransport,
        settings,
        AkkaPduProtobufCodec,
        createFailureDetector())), actorNameFor(remoteAddress))
  }

  private def createFailureDetector(): PhiAccrualFailureDetector = new PhiAccrualFailureDetector(
    settings.FailureDetectorThreshold,
    settings.FailureDetectorMaxSampleSize,
    settings.FailureDetectorStdDeviation,
    settings.AcceptableHeartBeatPause,
    settings.HeartBeatInterval)

  override def postStop() {
    wrappedTransport.shutdown()
  }

}

private[transport] class AkkaProtocolHandle(
  val localAddress: Address,
  val remoteAddress: Address,
  val readHandlerPromise: Promise[ActorRef],
  private val wrappedHandle: AssociationHandle,
  private val stateActor: ActorRef,
  private val codec: AkkaPduCodec)
  extends AssociationHandle {

  override def write(payload: ByteString): Boolean = wrappedHandle.write(codec.constructPayload(payload))

  override def disassociate(): Unit = stateActor ! DisassociateUnderlying

}

private[transport] object ProtocolStateActor {
  sealed trait AssociationState
  case object Closed extends AssociationState
  case object WaitActivity extends AssociationState
  case object Open extends AssociationState

  case object HeartbeatTimer

  sealed trait ProtocolStateData
  trait InitialProtocolStateData extends ProtocolStateData

  // Nor the underlying, nor the provided transport is associated
  case class OutboundUnassociated(remoteAddress: Address, statusPromise: Promise[Status], transport: Transport)
    extends InitialProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  case class OutboundUnderlyingAssociated(statusPromise: Promise[Status], wrappedHandle: AssociationHandle)
    extends ProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  case class InboundUnassociated(associationHandler: ActorRef, wrappedHandle: AssociationHandle)
    extends InitialProtocolStateData

  // Both transports are associated, but the handler for the handle has not yet been provided
  case class AssociatedWaitHandler(handlerFuture: Future[ActorRef], wrappedHandle: AssociationHandle, queue: Queue[ByteString])
    extends ProtocolStateData

  case class HandlerReady(handler: ActorRef, wrappedHandle: AssociationHandle)
    extends ProtocolStateData

  case object TimeoutReason
}

private[transport] class ProtocolStateActor(initialData: InitialProtocolStateData,
                                            private val localAddress: Address,
                                            private val settings: AkkaProtocolSettings,
                                            private val codec: AkkaPduCodec,
                                            private val failureDetector: FailureDetector)
  extends Actor with FSM[AssociationState, ProtocolStateData] {

  import ProtocolStateActor._
  import context.dispatcher

  // Outbound case
  def this(localAddress: Address,
           remoteAddress: Address,
           statusPromise: Promise[Status],
           transport: Transport,
           settings: AkkaProtocolSettings,
           codec: AkkaPduCodec,
           failureDetector: FailureDetector) = {
    this(OutboundUnassociated(remoteAddress, statusPromise, transport), localAddress, settings, codec, failureDetector)
  }

  // Inbound case
  def this(localAddress: Address,
           wrappedHandle: AssociationHandle,
           associationHandler: ActorRef,
           settings: AkkaProtocolSettings,
           codec: AkkaPduCodec,
           failureDetector: FailureDetector) = {
    this(InboundUnassociated(associationHandler, wrappedHandle), localAddress, settings, codec, failureDetector)
  }

  initialData match {
    case d: OutboundUnassociated ⇒
      d.transport.associate(removeScheme(d.remoteAddress)) pipeTo self
      startWith(Closed, d)

    case d: InboundUnassociated ⇒
      d.wrappedHandle.readHandlerPromise.success(self)
      startWith(Closed, d)
  }

  when(Closed) {

    // Transport layer events for outbound associations
    case Event(s @ Invalid(_), OutboundUnassociated(_, statusPromise, _)) ⇒
      statusPromise.success(s)
      stop()

    case Event(s @ Fail(_), OutboundUnassociated(_, statusPromise, _)) ⇒
      statusPromise.success(s)
      stop()

    case Event(Ready(wrappedHandle), OutboundUnassociated(_, statusPromise, _)) ⇒
      wrappedHandle.readHandlerPromise.success(self)
      sendAssociate(wrappedHandle)
      failureDetector.heartbeat()
      initTimers()

      if (settings.WaitActivityEnabled) {
        goto(WaitActivity) using OutboundUnderlyingAssociated(statusPromise, wrappedHandle)
      } else {
        goto(Open) using AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, Queue.empty)
      }

    // Events for inbound associations
    case Event(InboundPayload(p), InboundUnassociated(associationHandler, wrappedHandle)) ⇒
      decodePdu(p) match {
        // After receiving Disassociate we MUST NOT send back a Disassociate (loop)
        case Disassociate ⇒ stop()

        // Incoming association -- implicitly ACK by a heartbeat
        case Associate(cookieOption, origin) ⇒
          if (!settings.RequireCookie || cookieOption.getOrElse("") == settings.SecureCookie) {
            sendHeartbeat(wrappedHandle)

            failureDetector.heartbeat()
            initTimers()
            goto(Open) using AssociatedWaitHandler(notifyInboundHandler(wrappedHandle, origin, associationHandler), wrappedHandle, Queue.empty)
          } else {
            stop()
          }

        // Got a stray message -- explicitly reset the association (force remote endpoint to reassociate)
        case _ ⇒
          sendDisassociate(wrappedHandle)
          stop()

      }

    case Event(DisassociateUnderlying, _) ⇒
      stop()

    case _ ⇒ stay()

  }

  // Timeout of this state is implicitly handled by the failure detector
  when(WaitActivity) {
    case Event(Disassociated, OutboundUnderlyingAssociated(_, _)) ⇒
      stop()

    case Event(InboundPayload(p), OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        // Any other activity is considered an implicit acknowledgement of the association
        case Payload(payload) ⇒
          sendHeartbeat(wrappedHandle)
          goto(Open) using
            AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, Queue(payload))

        case Heartbeat ⇒
          sendHeartbeat(wrappedHandle)
          failureDetector.heartbeat()
          goto(Open) using
            AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, Queue.empty)

        case _ ⇒ goto(Open) using
          AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, Queue.empty)
      }

    case Event(HeartbeatTimer, OutboundUnderlyingAssociated(_, wrappedHandle)) ⇒ handleTimers(wrappedHandle)

  }

  when(Open) {
    case Event(Disassociated, _) ⇒
      stop()

    case Event(InboundPayload(p), AssociatedWaitHandler(handlerFuture, wrappedHandle, queue)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        case Heartbeat ⇒ failureDetector.heartbeat(); stay()

        case Payload(payload) ⇒
          // Queue message until handler is registered
          stay() using AssociatedWaitHandler(handlerFuture, wrappedHandle, queue :+ payload)

        case _ ⇒ stay()
      }

    case Event(InboundPayload(p), HandlerReady(handler, _)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        case Heartbeat ⇒ failureDetector.heartbeat(); stay()

        case Payload(payload) ⇒
          handler ! InboundPayload(payload)
          stay()

        case _ ⇒ stay()
      }

    case Event(HeartbeatTimer, AssociatedWaitHandler(_, wrappedHandle, _)) ⇒ handleTimers(wrappedHandle)
    case Event(HeartbeatTimer, HandlerReady(_, wrappedHandle))             ⇒ handleTimers(wrappedHandle)

    case Event(DisassociateUnderlying, HandlerReady(handler, wrappedHandle)) ⇒
      sendDisassociate(wrappedHandle)
      stop()

    case Event(HandlerRegistered(ref), AssociatedWaitHandler(_, wrappedHandle, queue)) ⇒
      queue.foreach { ref ! InboundPayload(_) }
      stay() using HandlerReady(ref, wrappedHandle)
  }

  private def initTimers(): Unit = {
    setTimer("heartbeat-timer", HeartbeatTimer, settings.HeartBeatInterval, repeat = true)
  }

  private def handleTimers(wrappedHandle: AssociationHandle): State = {
    if (failureDetector.isAvailable) {
      sendHeartbeat(wrappedHandle)
      stay()
    } else {
      // send disassociate just to be sure
      sendDisassociate(wrappedHandle)
      stop(FSM.Failure(TimeoutReason))
    }
  }

  override def postStop(): Unit = {
    cancelTimer("heartbeat-timer")
    super.postStop() // Pass to onTermination
  }

  onTermination {
    case StopEvent(_, _, OutboundUnassociated(remoteAddress, statusPromise, transport)) ⇒
      statusPromise.trySuccess(Fail(new AkkaProtocolException("Transport disassociated before handshake finished", null)))

    case StopEvent(reason, _, OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      val msg = reason match {
        case FSM.Failure(TimeoutReason) ⇒ "No response from remote. Handshake timed out."
        case _                          ⇒ "Remote endpoint disassociated before handshake finished"
      }
      statusPromise.trySuccess(Fail(new AkkaProtocolException(msg, null)))
      wrappedHandle.disassociate()

    case StopEvent(_, _, AssociatedWaitHandler(handlerFuture, wrappedHandle, queue)) ⇒
      // Invalidate exposed but still unfinished promise. The underlying association disappeared, so after
      // registration immediately signal a disassociate
      handlerFuture.onSuccess {
        case handler: ActorRef ⇒ handler ! Disassociated
      }

    case StopEvent(_, _, HandlerReady(handler, wrappedHandle)) ⇒
      handler ! Disassociated
      wrappedHandle.disassociate()

    case StopEvent(_, _, InboundUnassociated(_, wrappedHandle)) ⇒
      wrappedHandle.disassociate()
  }

  private def notifyOutboundHandler(wrappedHandle: AssociationHandle, statusPromise: Promise[Status]): Future[ActorRef] = {
    val readHandlerPromise: Promise[ActorRef] = Promise()
    readHandlerPromise.future.map { HandlerRegistered(_) } pipeTo self

    val exposedHandle =
      new AkkaProtocolHandle(
        augmentScheme(localAddress),
        augmentScheme(wrappedHandle.remoteAddress),
        readHandlerPromise,
        wrappedHandle,
        self,
        codec)

    statusPromise.success(Ready(exposedHandle))
    readHandlerPromise.future
  }

  private def notifyInboundHandler(wrappedHandle: AssociationHandle, originAddress: Address, associationHandler: ActorRef): Future[ActorRef] = {
    val readHandlerPromise: Promise[ActorRef] = Promise()
    readHandlerPromise.future.map { HandlerRegistered(_) } pipeTo self

    val exposedHandle =
      new AkkaProtocolHandle(
        augmentScheme(localAddress),
        augmentScheme(originAddress),
        readHandlerPromise,
        wrappedHandle,
        self,
        codec)

    associationHandler ! InboundAssociation(exposedHandle)
    readHandlerPromise.future
  }

  // Helper method for exception translation
  private def ape[T](errorMsg: String)(body: ⇒ T): T = try body catch {
    case NonFatal(e) ⇒ throw new AkkaProtocolException(errorMsg, e)
  }

  private def decodePdu(pdu: ByteString): AkkaPdu = ape("Error while decoding incoming Akka PDU of length: " + pdu.length) {
    codec.decodePdu(pdu)
  }

  // Neither heartbeats neither disassociate cares about backing off if write fails:
  //  - Missing heartbeats are not critical
  //  - Disassociate messages are not guaranteed anyway
  private def sendHeartbeat(wrappedHandle: AssociationHandle): Unit = ape("Error writing HEARTBEAT to transport") {
    wrappedHandle.write(codec.constructHeartbeat)
  }

  private def sendDisassociate(wrappedHandle: AssociationHandle): Unit = ape("Error writing DISASSOCIATE to transport") {
    wrappedHandle.write(codec.constructDisassociate)
  }

  // Associate should be the first message, so backoff is not needed
  private def sendAssociate(wrappedHandle: AssociationHandle): Unit = ape("Error writing ASSOCIATE to transport") {
    val cookie = if (settings.RequireCookie) Some(settings.SecureCookie) else None
    wrappedHandle.write(codec.constructAssociate(cookie, localAddress))
  }

}
