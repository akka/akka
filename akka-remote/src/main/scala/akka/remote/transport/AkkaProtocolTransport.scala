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
import scala.collection.immutable
import akka.remote.transport.ActorTransportAdapter._

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

}

/**
 * Implementation of the Akka protocol as a Transport that wraps an underlying Transport instance.
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
  wrappedTransport: Transport,
  private val system: ActorSystem,
  private val settings: AkkaProtocolSettings,
  private val codec: AkkaPduCodec) extends ActorTransportAdapter(wrappedTransport, system) {

  override val addedSchemeIdentifier: String = AkkaScheme

  override def managementCommand(cmd: Any, statusPromise: Promise[Boolean]): Unit =
    wrappedTransport.managementCommand(cmd, statusPromise)

  override val maximumOverhead: Int = AkkaProtocolTransport.AkkaOverhead
  protected def managerName = s"akkaprotocolmanager.${wrappedTransport.schemeIdentifier}${UniqueId.getAndIncrement}"
  protected def managerProps = {
    val wt = wrappedTransport
    val s = settings
    Props(new AkkaProtocolManager(wt, s))
  }
}

private[transport] class AkkaProtocolManager(
  private val wrappedTransport: Transport,
  private val settings: AkkaProtocolSettings)
  extends Actor {

  import context.dispatcher

  // The AkkaProtocolTransport does not handle the recovery of associations, this task is implemented in the
  // remoting itself. Hence the strategy Stop.
  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(_) ⇒ Stop
  }

  private val nextId = Iterator from 0

  var localAddress: Address = _

  private var associationHandler: AssociationEventListener = _

  def receive: Receive = {
    case ListenUnderlying(listenAddress, upstreamListenerFuture) ⇒
      localAddress = listenAddress
      upstreamListenerFuture.future.map { ListenerRegistered(_) } pipeTo self

    case ListenerRegistered(listener) ⇒
      associationHandler = listener
      context.become(ready)

    // Block inbound associations until handler is registered
    case InboundAssociation(handle) ⇒ handle.disassociate()
  }

  private def actorNameFor(remoteAddress: Address): String =
    "akkaProtocol-" + URLEncoder.encode(remoteAddress.toString, "utf-8") + "-" + nextId.next()

  private def ready: Receive = {
    case InboundAssociation(handle) ⇒
      val stateActorLocalAddress = localAddress
      val stateActorAssociationHandler = associationHandler
      val stateActorSettings = settings
      val failureDetector = createFailureDetector()
      context.actorOf(Props(new ProtocolStateActor(
        stateActorLocalAddress,
        handle,
        stateActorAssociationHandler,
        stateActorSettings,
        AkkaPduProtobufCodec,
        failureDetector)), actorNameFor(handle.remoteAddress))

    case AssociateUnderlying(remoteAddress, statusPromise) ⇒
      val stateActorLocalAddress = localAddress
      val stateActorSettings = settings
      val stateActorWrappedTransport = wrappedTransport
      val failureDetector = createFailureDetector()
      context.actorOf(Props(new ProtocolStateActor(
        stateActorLocalAddress,
        remoteAddress,
        statusPromise,
        stateActorWrappedTransport,
        stateActorSettings,
        AkkaPduProtobufCodec,
        failureDetector)), actorNameFor(remoteAddress))
  }

  private def createFailureDetector(): FailureDetector = new PhiAccrualFailureDetector(
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
  _localAddress: Address,
  _remoteAddress: Address,
  val readHandlerPromise: Promise[HandleEventListener],
  _wrappedHandle: AssociationHandle,
  private val stateActor: ActorRef,
  private val codec: AkkaPduCodec)
  extends AbstractTransportAdapterHandle(_localAddress, _remoteAddress, _wrappedHandle, AkkaScheme) {

  override def write(payload: ByteString): Boolean = wrappedHandle.write(codec.constructPayload(payload))

  override def disassociate(): Unit = stateActor ! DisassociateUnderlying

}

private[transport] object ProtocolStateActor {
  sealed trait AssociationState

  /*
   * State when the underlying transport is not yet initialized
   * State data can be OutboundUnassociated
   */
  case object Closed extends AssociationState

  /*
   * State when the underlying transport is initialized, there is an association present, and we are waiting
   * for the first message. Outbound connections can skip this phase if WaitActivity configuration parameter
   * is turned off.
   * State data can be OutboundUnderlyingAssociated (for outbound associations) or InboundUnassociated (for inbound
   * when upper layer is not notified yet)
   */
  case object WaitActivity extends AssociationState

  /*
   * State when the underlying transport is initialized and the handshake succeeded.
   * If the upper layer did not yet provided a handler for incoming messages, state data is AssociatedWaitHandler.
   * If everything is initialized, the state data is HandlerReady
   */
  case object Open extends AssociationState

  case object HeartbeatTimer

  case class HandleListenerRegistered(listener: HandleEventListener)

  sealed trait ProtocolStateData
  trait InitialProtocolStateData extends ProtocolStateData

  // Neither the underlying, nor the provided transport is associated
  case class OutboundUnassociated(remoteAddress: Address, statusPromise: Promise[Status], transport: Transport)
    extends InitialProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  case class OutboundUnderlyingAssociated(statusPromise: Promise[Status], wrappedHandle: AssociationHandle)
    extends ProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  case class InboundUnassociated(associationListener: AssociationEventListener, wrappedHandle: AssociationHandle)
    extends InitialProtocolStateData

  // Both transports are associated, but the handler for the handle has not yet been provided
  case class AssociatedWaitHandler(handleListener: Future[HandleEventListener], wrappedHandle: AssociationHandle,
                                   queue: immutable.Queue[ByteString])
    extends ProtocolStateData

  case class ListenerReady(listener: HandleEventListener, wrappedHandle: AssociationHandle)
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
           associationListener: AssociationEventListener,
           settings: AkkaProtocolSettings,
           codec: AkkaPduCodec,
           failureDetector: FailureDetector) = {
    this(InboundUnassociated(associationListener, wrappedHandle), localAddress, settings, codec, failureDetector)
  }

  initialData match {
    case d: OutboundUnassociated ⇒
      d.transport.associate(d.remoteAddress) pipeTo self
      startWith(Closed, d)

    case d: InboundUnassociated ⇒
      d.wrappedHandle.readHandlerPromise.success(self)
      startWith(WaitActivity, d)
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

      if (settings.WaitActivityEnabled)
        goto(WaitActivity) using OutboundUnderlyingAssociated(statusPromise, wrappedHandle)
      else
        goto(Open) using AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, immutable.Queue.empty)

    case Event(DisassociateUnderlying, _) ⇒
      stop()

    case _ ⇒ stay()

  }

  // Timeout of this state is implicitly handled by the failure detector
  when(WaitActivity) {
    case Event(Disassociated, _) ⇒
      stop()

    case Event(InboundPayload(p), OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        // Any other activity is considered an implicit acknowledgement of the association
        case Payload(payload) ⇒
          sendHeartbeat(wrappedHandle)
          goto(Open) using
            AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, immutable.Queue(payload))

        case Heartbeat ⇒
          sendHeartbeat(wrappedHandle)
          failureDetector.heartbeat()
          goto(Open) using
            AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, immutable.Queue.empty)

        case _ ⇒ goto(Open) using
          AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, immutable.Queue.empty)
      }

    case Event(HeartbeatTimer, OutboundUnderlyingAssociated(_, wrappedHandle)) ⇒ handleTimers(wrappedHandle)

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
            goto(Open) using AssociatedWaitHandler(notifyInboundHandler(wrappedHandle, origin, associationHandler), wrappedHandle, immutable.Queue.empty)
          } else {
            stop()
          }

        // Got a stray message -- explicitly reset the association (force remote endpoint to reassociate)
        case _ ⇒
          sendDisassociate(wrappedHandle)
          stop()

      }

  }

  when(Open) {
    case Event(Disassociated, _) ⇒
      stop()

    case Event(InboundPayload(p), _) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        case Heartbeat ⇒ failureDetector.heartbeat(); stay()

        case Payload(payload) ⇒ stateData match {
          case AssociatedWaitHandler(handlerFuture, wrappedHandle, queue) ⇒
            // Queue message until handler is registered
            stay() using AssociatedWaitHandler(handlerFuture, wrappedHandle, queue :+ payload)
          case ListenerReady(listener, _) ⇒
            listener notify InboundPayload(payload)
            stay()
        }

        case _ ⇒ stay()
      }

    case Event(HeartbeatTimer, AssociatedWaitHandler(_, wrappedHandle, _)) ⇒ handleTimers(wrappedHandle)
    case Event(HeartbeatTimer, ListenerReady(_, wrappedHandle))            ⇒ handleTimers(wrappedHandle)

    case Event(DisassociateUnderlying, _) ⇒
      val handle = stateData match {
        case ListenerReady(_, wrappedHandle)            ⇒ wrappedHandle
        case AssociatedWaitHandler(_, wrappedHandle, _) ⇒ wrappedHandle
      }
      sendDisassociate(handle)
      stop()

    case Event(HandleListenerRegistered(listener), AssociatedWaitHandler(_, wrappedHandle, queue)) ⇒
      queue.foreach { listener notify InboundPayload(_) }
      stay() using ListenerReady(listener, wrappedHandle)
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
      handlerFuture foreach { _ notify Disassociated }

    case StopEvent(_, _, ListenerReady(handler, wrappedHandle)) ⇒
      handler notify Disassociated
      wrappedHandle.disassociate()

    case StopEvent(_, _, InboundUnassociated(_, wrappedHandle)) ⇒
      wrappedHandle.disassociate()
  }

  private def notifyOutboundHandler(wrappedHandle: AssociationHandle,
                                    statusPromise: Promise[Status]): Future[HandleEventListener] = {
    val readHandlerPromise: Promise[HandleEventListener] = Promise()
    readHandlerPromise.future.map { HandleListenerRegistered(_) } pipeTo self

    val exposedHandle =
      new AkkaProtocolHandle(
        localAddress,
        wrappedHandle.remoteAddress,
        readHandlerPromise,
        wrappedHandle,
        self,
        codec)

    statusPromise.success(Ready(exposedHandle))
    readHandlerPromise.future
  }

  private def notifyInboundHandler(wrappedHandle: AssociationHandle,
                                   originAddress: Address,
                                   associationListener: AssociationEventListener): Future[HandleEventListener] = {
    val readHandlerPromise: Promise[HandleEventListener] = Promise()
    readHandlerPromise.future.map { HandleListenerRegistered(_) } pipeTo self

    val exposedHandle =
      new AkkaProtocolHandle(
        localAddress,
        originAddress,
        readHandlerPromise,
        wrappedHandle,
        self,
        codec)

    associationListener notify InboundAssociation(exposedHandle)
    readHandlerPromise.future
  }

  // Helper method for exception translation
  private def tryOrThrowAkkaProtocolException[T](errorMsg: String)(body: ⇒ T): T = try body catch {
    case NonFatal(e) ⇒ throw new AkkaProtocolException(errorMsg, e)
  }

  private def decodePdu(pdu: ByteString): AkkaPdu = {
    tryOrThrowAkkaProtocolException("Error while decoding incoming Akka PDU of length: " + pdu.length) {
      codec.decodePdu(pdu)
    }
  }

  // Neither heartbeats neither disassociate cares about backing off if write fails:
  //  - Missing heartbeats are not critical
  //  - Disassociate messages are not guaranteed anyway
  private def sendHeartbeat(wrappedHandle: AssociationHandle): Unit = {
    tryOrThrowAkkaProtocolException("Error writing HEARTBEAT to transport") {
      wrappedHandle.write(codec.constructHeartbeat)
    }
  }

  private def sendDisassociate(wrappedHandle: AssociationHandle): Unit = {
    tryOrThrowAkkaProtocolException("Error writing DISASSOCIATE to transport") {
      wrappedHandle.write(codec.constructDisassociate)
    }
  }

  // Associate should be the first message, so backoff is not needed
  private def sendAssociate(wrappedHandle: AssociationHandle): Unit = {
    tryOrThrowAkkaProtocolException("Error writing ASSOCIATE to transport") {
      val cookie = if (settings.RequireCookie) Some(settings.SecureCookie) else None
      wrappedHandle.write(codec.constructAssociate(cookie, localAddress))
    }
  }

}
