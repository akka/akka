/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.transport

import akka.ConfigurationException
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.pattern.pipe
import akka.remote._
import akka.remote.transport.ActorTransportAdapter._
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AkkaProtocolTransport._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.ProtocolStateActor._
import akka.remote.transport.Transport._
import akka.util.ByteString
import akka.util.Helpers.Requiring
import akka.{ OnlyCauseStackTrace, AkkaException }
import com.typesafe.config.Config
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

@SerialVersionUID(1L)
class AkkaProtocolException(msg: String, cause: Throwable) extends AkkaException(msg, cause) with OnlyCauseStackTrace {
  def this(msg: String) = this(msg, null)
}

private[remote] class AkkaProtocolSettings(config: Config) {

  import config._

  val TransportFailureDetectorConfig: Config = getConfig("akka.remote.transport-failure-detector")
  val TransportFailureDetectorImplementationClass: String = TransportFailureDetectorConfig.getString("implementation-class")
  val TransportHeartBeatInterval: FiniteDuration = {
    Duration(TransportFailureDetectorConfig.getMilliseconds("heartbeat-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "transport-failure-detector.heartbeat-interval must be > 0")

  val WaitActivityEnabled: Boolean = getBoolean("akka.remote.wait-activity-enabled")

  val RequireCookie: Boolean = getBoolean("akka.remote.require-cookie")

  val SecureCookie: String = getString("akka.remote.secure-cookie")
}

private[remote] object AkkaProtocolTransport { //Couldn't these go into the Remoting Extension/ RemoteSettings instead?
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

  override def managementCommand(cmd: Any): Future[Boolean] = wrappedTransport.managementCommand(cmd)

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
  extends ActorTransportAdapterManager {

  // The AkkaProtocolTransport does not handle the recovery of associations, this task is implemented in the
  // remoting itself. Hence the strategy Stop.
  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(_) ⇒ Stop
  }

  private def actorNameFor(remoteAddress: Address): String =
    "akkaProtocol-" + AddressUrlEncoder(remoteAddress) + "-" + nextId()

  override def ready: Receive = {
    case InboundAssociation(handle) ⇒
      val stateActorLocalAddress = localAddress
      val stateActorAssociationHandler = associationListener
      val stateActorSettings = settings
      val failureDetector = createTransportFailureDetector()
      context.actorOf(Props(new ProtocolStateActor(
        stateActorLocalAddress,
        handle,
        stateActorAssociationHandler,
        stateActorSettings,
        AkkaPduProtobufCodec,
        failureDetector)), actorNameFor(handle.remoteAddress)) // Why don't we watch this one?

    case AssociateUnderlying(remoteAddress, statusPromise) ⇒
      val stateActorLocalAddress = localAddress
      val stateActorSettings = settings
      val stateActorWrappedTransport = wrappedTransport
      val failureDetector = createTransportFailureDetector()
      context.actorOf(Props(new ProtocolStateActor(
        stateActorLocalAddress,
        remoteAddress,
        statusPromise,
        stateActorWrappedTransport,
        stateActorSettings,
        AkkaPduProtobufCodec,
        failureDetector)), actorNameFor(remoteAddress)) // Why don't we watch this one?
  }

  private def createTransportFailureDetector(): FailureDetector = {
    import settings.{ TransportFailureDetectorImplementationClass ⇒ fqcn }
    context.system.asInstanceOf[ExtendedActorSystem].dynamicAccess.createInstanceFor[FailureDetector](
      fqcn, List(classOf[Config] -> settings.TransportFailureDetectorConfig)).recover({
        case e ⇒ throw new ConfigurationException(
          s"Could not create custom remote failure detector [$fqcn] due to: ${e.toString}", e)
      }).get
  }

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
  case class OutboundUnassociated(remoteAddress: Address, statusPromise: Promise[AssociationHandle], transport: Transport)
    extends InitialProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  case class OutboundUnderlyingAssociated(statusPromise: Promise[AssociationHandle], wrappedHandle: AssociationHandle)
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
           statusPromise: Promise[AssociationHandle],
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
      d.wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(self))
      startWith(WaitActivity, d)
  }

  when(Closed) {

    // Transport layer events for outbound associations
    case Event(Status.Failure(e), OutboundUnassociated(_, statusPromise, _)) ⇒
      statusPromise.failure(e)
      stop()

    case Event(wrappedHandle: AssociationHandle, OutboundUnassociated(_, statusPromise, _)) ⇒
      wrappedHandle.readHandlerPromise.trySuccess(ActorHandleEventListener(self))
      if (sendAssociate(wrappedHandle)) {
        failureDetector.heartbeat()
        initTimers()

        if (settings.WaitActivityEnabled)
          goto(WaitActivity) using OutboundUnderlyingAssociated(statusPromise, wrappedHandle)
        else
          goto(Open) using AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, immutable.Queue.empty)
      } else {
        // Underlying transport was busy -- Associate could not be sent
        setTimer("associate-retry", wrappedHandle, RARP(context.system).provider.remoteSettings.BackoffPeriod, repeat = false)
        stay()
      }

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

        case Heartbeat ⇒
          failureDetector.heartbeat(); stay()

        case Payload(payload) ⇒ stateData match {
          case AssociatedWaitHandler(handlerFuture, wrappedHandle, queue) ⇒
            // Queue message until handler is registered
            stay() using AssociatedWaitHandler(handlerFuture, wrappedHandle, queue :+ payload)
          case ListenerReady(listener, _) ⇒
            listener notify InboundPayload(payload)
            stay()
          case msg ⇒
            throw new AkkaProtocolException("unhandled message in Open state with type " + (if (msg ne null) msg.getClass else "null"))
        }

        case _ ⇒ stay()
      }

    case Event(HeartbeatTimer, AssociatedWaitHandler(_, wrappedHandle, _)) ⇒ handleTimers(wrappedHandle)
    case Event(HeartbeatTimer, ListenerReady(_, wrappedHandle))            ⇒ handleTimers(wrappedHandle)

    case Event(DisassociateUnderlying, _) ⇒
      val handle = stateData match {
        case ListenerReady(_, wrappedHandle)            ⇒ wrappedHandle
        case AssociatedWaitHandler(_, wrappedHandle, _) ⇒ wrappedHandle
        case msg ⇒
          throw new AkkaProtocolException("unhandled message in Open state with type " + (if (msg ne null) msg.getClass else "null"))
      }
      sendDisassociate(handle)
      stop()

    case Event(HandleListenerRegistered(listener), AssociatedWaitHandler(_, wrappedHandle, queue)) ⇒
      queue.foreach { listener notify InboundPayload(_) }
      stay() using ListenerReady(listener, wrappedHandle)
  }

  private def initTimers(): Unit = {
    setTimer("heartbeat-timer", HeartbeatTimer, settings.TransportHeartBeatInterval, repeat = true)
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
      statusPromise.tryFailure(new AkkaProtocolException("Transport disassociated before handshake finished"))

    case StopEvent(reason, _, OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      statusPromise.tryFailure(new AkkaProtocolException(reason match {
        case FSM.Failure(TimeoutReason) ⇒ "No response from remote. Handshake timed out"
        case _                          ⇒ "Remote endpoint disassociated before handshake finished"
      }))
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

  private def listenForListenerRegistration(readHandlerPromise: Promise[HandleEventListener]): Unit =
    readHandlerPromise.future.map { HandleListenerRegistered(_) } pipeTo self

  private def notifyOutboundHandler(wrappedHandle: AssociationHandle,
                                    statusPromise: Promise[AssociationHandle]): Future[HandleEventListener] = {
    val readHandlerPromise = Promise[HandleEventListener]()
    listenForListenerRegistration(readHandlerPromise)

    statusPromise.success(
      new AkkaProtocolHandle(
        localAddress,
        wrappedHandle.remoteAddress,
        readHandlerPromise,
        wrappedHandle,
        self,
        codec))
    readHandlerPromise.future
  }

  private def notifyInboundHandler(wrappedHandle: AssociationHandle,
                                   originAddress: Address,
                                   associationListener: AssociationEventListener): Future[HandleEventListener] = {
    val readHandlerPromise = Promise[HandleEventListener]()
    listenForListenerRegistration(readHandlerPromise)

    associationListener notify InboundAssociation(
      new AkkaProtocolHandle(
        localAddress,
        originAddress,
        readHandlerPromise,
        wrappedHandle,
        self,
        codec))
    readHandlerPromise.future
  }

  private def decodePdu(pdu: ByteString): AkkaPdu = try codec.decodePdu(pdu) catch {
    case NonFatal(e) ⇒ throw new AkkaProtocolException("Error while decoding incoming Akka PDU of length: " + pdu.length, e)
  }

  // Neither heartbeats neither disassociate cares about backing off if write fails:
  //  - Missing heartbeats are not critical
  //  - Disassociate messages are not guaranteed anyway
  private def sendHeartbeat(wrappedHandle: AssociationHandle): Unit = try wrappedHandle.write(codec.constructHeartbeat) catch {
    case NonFatal(e) ⇒ throw new AkkaProtocolException("Error writing HEARTBEAT to transport", e)
  }

  private def sendDisassociate(wrappedHandle: AssociationHandle): Unit = try wrappedHandle.write(codec.constructDisassociate) catch {
    case NonFatal(e) ⇒ throw new AkkaProtocolException("Error writing DISASSOCIATE to transport", e)
  }

  private def sendAssociate(wrappedHandle: AssociationHandle): Boolean = try {
    val cookie = if (settings.RequireCookie) Some(settings.SecureCookie) else None
    wrappedHandle.write(codec.constructAssociate(cookie, localAddress))
  } catch {
    case NonFatal(e) ⇒ throw new AkkaProtocolException("Error writing ASSOCIATE to transport", e)
  }
}
