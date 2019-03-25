/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport

import java.util.concurrent.TimeoutException

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
import akka.{ AkkaException, OnlyCauseStackTrace }
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.event.{ LogMarker, Logging }

@SerialVersionUID(1L)
class AkkaProtocolException(msg: String, cause: Throwable) extends AkkaException(msg, cause) with OnlyCauseStackTrace {
  def this(msg: String) = this(msg, null)
}

private[remote] class AkkaProtocolSettings(config: Config) {

  import akka.util.Helpers.ConfigOps
  import config._

  val TransportFailureDetectorConfig: Config = getConfig("akka.remote.transport-failure-detector")
  val TransportFailureDetectorImplementationClass: String =
    TransportFailureDetectorConfig.getString("implementation-class")
  val TransportHeartBeatInterval: FiniteDuration = {
    TransportFailureDetectorConfig.getMillisDuration("heartbeat-interval")
  }.requiring(_ > Duration.Zero, "transport-failure-detector.heartbeat-interval must be > 0")

  val RequireCookie: Boolean = getBoolean("akka.remote.require-cookie")

  val SecureCookie: Option[String] = if (RequireCookie) Some(getString("akka.remote.secure-cookie")) else None

  val HandshakeTimeout: FiniteDuration = {
    val enabledTransports = config.getStringList("akka.remote.enabled-transports")
    if (enabledTransports.contains("akka.remote.netty.tcp"))
      config.getMillisDuration("akka.remote.netty.tcp.connection-timeout")
    else if (enabledTransports.contains("akka.remote.netty.ssl"))
      config.getMillisDuration("akka.remote.netty.ssl.connection-timeout")
    else
      config
        .getMillisDuration("akka.remote.handshake-timeout")
        .requiring(_ > Duration.Zero, "handshake-timeout must be > 0")
  }
}

private[remote] object AkkaProtocolTransport { //Couldn't these go into the Remoting Extension/ RemoteSettings instead?
  val AkkaScheme: String = "akka"
  val AkkaOverhead: Int = 0 //Don't know yet
  val UniqueId = new java.util.concurrent.atomic.AtomicInteger(0)

  final case class AssociateUnderlyingRefuseUid(
      remoteAddress: Address,
      statusPromise: Promise[AssociationHandle],
      refuseUid: Option[Int])
      extends NoSerializationVerificationNeeded
}

final case class HandshakeInfo(origin: Address, uid: Int, cookie: Option[String])

/**
 * Implementation of the Akka protocol as a Transport that wraps an underlying Transport instance.
 *
 * Features provided by this transport are:
 *  - Soft-state associations via the use of heartbeats and failure detectors
 *  - Secure-cookie handling
 *  - Transparent origin address handling
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
    private val codec: AkkaPduCodec)
    extends ActorTransportAdapter(wrappedTransport, system) {

  override val addedSchemeIdentifier: String = AkkaScheme

  override def managementCommand(cmd: Any): Future[Boolean] = wrappedTransport.managementCommand(cmd)

  def associate(remoteAddress: Address, refuseUid: Option[Int]): Future[AkkaProtocolHandle] = {
    // Prepare a future, and pass its promise to the manager
    val statusPromise: Promise[AssociationHandle] = Promise()

    manager ! AssociateUnderlyingRefuseUid(removeScheme(remoteAddress), statusPromise, refuseUid)

    statusPromise.future.mapTo[AkkaProtocolHandle]
  }

  override val maximumOverhead: Int = AkkaProtocolTransport.AkkaOverhead
  protected def managerName = s"akkaprotocolmanager.${wrappedTransport.schemeIdentifier}${UniqueId.getAndIncrement}"
  protected def managerProps = {
    val wt = wrappedTransport
    val s = settings
    Props(classOf[AkkaProtocolManager], wt, s).withDeploy(Deploy.local)
  }
}

private[transport] class AkkaProtocolManager(
    private val wrappedTransport: Transport,
    private val settings: AkkaProtocolSettings)
    extends ActorTransportAdapterManager {

  // The AkkaProtocolTransport does not handle the recovery of associations, this task is implemented in the
  // remoting itself. Hence the strategy Stop.
  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(_) => Stop
  }

  private def actorNameFor(remoteAddress: Address): String =
    "akkaProtocol-" + AddressUrlEncoder(remoteAddress) + "-" + nextId()

  override def ready: Receive = {
    case InboundAssociation(handle) =>
      val stateActorLocalAddress = localAddress
      val stateActorAssociationHandler = associationListener
      val stateActorSettings = settings
      val failureDetector = createTransportFailureDetector()
      context.actorOf(
        RARP(context.system).configureDispatcher(
          ProtocolStateActor.inboundProps(
            HandshakeInfo(
              stateActorLocalAddress,
              AddressUidExtension(context.system).addressUid,
              stateActorSettings.SecureCookie),
            handle,
            stateActorAssociationHandler,
            stateActorSettings,
            AkkaPduProtobufCodec,
            failureDetector)),
        actorNameFor(handle.remoteAddress))

    case AssociateUnderlying(remoteAddress, statusPromise) =>
      createOutboundStateActor(remoteAddress, statusPromise, None)
    case AssociateUnderlyingRefuseUid(remoteAddress, statusPromise, refuseUid) =>
      createOutboundStateActor(remoteAddress, statusPromise, refuseUid)

  }

  private def createOutboundStateActor(
      remoteAddress: Address,
      statusPromise: Promise[AssociationHandle],
      refuseUid: Option[Int]): Unit = {

    val stateActorLocalAddress = localAddress
    val stateActorSettings = settings
    val stateActorWrappedTransport = wrappedTransport
    val failureDetector = createTransportFailureDetector()
    context.actorOf(
      RARP(context.system).configureDispatcher(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(
            stateActorLocalAddress,
            AddressUidExtension(context.system).addressUid,
            stateActorSettings.SecureCookie),
          remoteAddress,
          statusPromise,
          stateActorWrappedTransport,
          stateActorSettings,
          AkkaPduProtobufCodec,
          failureDetector,
          refuseUid)),
      actorNameFor(remoteAddress))
  }

  private def createTransportFailureDetector(): FailureDetector =
    FailureDetectorLoader(settings.TransportFailureDetectorImplementationClass, settings.TransportFailureDetectorConfig)

}

private[remote] class AkkaProtocolHandle(
    _localAddress: Address,
    _remoteAddress: Address,
    val readHandlerPromise: Promise[HandleEventListener],
    _wrappedHandle: AssociationHandle,
    val handshakeInfo: HandshakeInfo,
    private val stateActor: ActorRef,
    private val codec: AkkaPduCodec)
    extends AbstractTransportAdapterHandle(_localAddress, _remoteAddress, _wrappedHandle, AkkaScheme) {

  override def write(payload: ByteString): Boolean = wrappedHandle.write(codec.constructPayload(payload))

  override def disassociate(): Unit = disassociate(Unknown)

  def disassociate(info: DisassociateInfo): Unit = stateActor ! DisassociateUnderlying(info)
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
   * for the first message (has to be CONNECT if inbound).
   * State data can be OutboundUnderlyingAssociated (for outbound associations) or InboundUnassociated (for inbound
   * when upper layer is not notified yet)
   */
  case object WaitHandshake extends AssociationState

  /*
   * State when the underlying transport is initialized and the handshake succeeded.
   * If the upper layer did not yet provided a handler for incoming messages, state data is AssociatedWaitHandler.
   * If everything is initialized, the state data is HandlerReady
   */
  case object Open extends AssociationState

  case object HeartbeatTimer extends NoSerializationVerificationNeeded

  case object HandshakeTimer extends NoSerializationVerificationNeeded

  final case class Handle(handle: AssociationHandle) extends NoSerializationVerificationNeeded

  final case class HandleListenerRegistered(listener: HandleEventListener) extends NoSerializationVerificationNeeded

  sealed trait ProtocolStateData
  trait InitialProtocolStateData extends ProtocolStateData

  // Neither the underlying, nor the provided transport is associated
  final case class OutboundUnassociated(
      remoteAddress: Address,
      statusPromise: Promise[AssociationHandle],
      transport: Transport)
      extends InitialProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  final case class OutboundUnderlyingAssociated(
      statusPromise: Promise[AssociationHandle],
      wrappedHandle: AssociationHandle)
      extends ProtocolStateData

  // The underlying transport is associated, but the handshake of the akka protocol is not yet finished
  final case class InboundUnassociated(associationListener: AssociationEventListener, wrappedHandle: AssociationHandle)
      extends InitialProtocolStateData

  // Both transports are associated, but the handler for the handle has not yet been provided
  final case class AssociatedWaitHandler(
      handleListener: Future[HandleEventListener],
      wrappedHandle: AssociationHandle,
      queue: immutable.Queue[ByteString])
      extends ProtocolStateData

  final case class ListenerReady(listener: HandleEventListener, wrappedHandle: AssociationHandle)
      extends ProtocolStateData

  case class TimeoutReason(errorMessage: String)
  case object ForbiddenUidReason

  private[remote] def outboundProps(
      handshakeInfo: HandshakeInfo,
      remoteAddress: Address,
      statusPromise: Promise[AssociationHandle],
      transport: Transport,
      settings: AkkaProtocolSettings,
      codec: AkkaPduCodec,
      failureDetector: FailureDetector,
      refuseUid: Option[Int]): Props =
    Props(
      classOf[ProtocolStateActor],
      handshakeInfo,
      remoteAddress,
      statusPromise,
      transport,
      settings,
      codec,
      failureDetector,
      refuseUid).withDeploy(Deploy.local)

  private[remote] def inboundProps(
      handshakeInfo: HandshakeInfo,
      wrappedHandle: AssociationHandle,
      associationListener: AssociationEventListener,
      settings: AkkaProtocolSettings,
      codec: AkkaPduCodec,
      failureDetector: FailureDetector): Props =
    Props(
      classOf[ProtocolStateActor],
      handshakeInfo,
      wrappedHandle,
      associationListener,
      settings,
      codec,
      failureDetector).withDeploy(Deploy.local)
}

private[transport] class ProtocolStateActor(
    initialData: InitialProtocolStateData,
    private val localHandshakeInfo: HandshakeInfo,
    private val refuseUid: Option[Int],
    private val settings: AkkaProtocolSettings,
    private val codec: AkkaPduCodec,
    private val failureDetector: FailureDetector)
    extends Actor
    with FSM[AssociationState, ProtocolStateData]
    with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  private val markerLog = Logging.withMarker(this)

  import ProtocolStateActor._
  import context.dispatcher

  // Outbound case
  def this(
      handshakeInfo: HandshakeInfo,
      remoteAddress: Address,
      statusPromise: Promise[AssociationHandle],
      transport: Transport,
      settings: AkkaProtocolSettings,
      codec: AkkaPduCodec,
      failureDetector: FailureDetector,
      refuseUid: Option[Int]) = {
    this(
      OutboundUnassociated(remoteAddress, statusPromise, transport),
      handshakeInfo,
      refuseUid,
      settings,
      codec,
      failureDetector)
  }

  // Inbound case
  def this(
      handshakeInfo: HandshakeInfo,
      wrappedHandle: AssociationHandle,
      associationListener: AssociationEventListener,
      settings: AkkaProtocolSettings,
      codec: AkkaPduCodec,
      failureDetector: FailureDetector) = {
    this(
      InboundUnassociated(associationListener, wrappedHandle),
      handshakeInfo,
      refuseUid = None,
      settings,
      codec,
      failureDetector)
  }

  val localAddress = localHandshakeInfo.origin
  val handshakeTimerKey = "handshake-timer"

  initialData match {
    case d: OutboundUnassociated =>
      d.transport.associate(d.remoteAddress).map(Handle(_)).pipeTo(self)
      startWith(Closed, d)

    case d: InboundUnassociated =>
      d.wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(self))
      initHandshakeTimer()
      startWith(WaitHandshake, d)
  }

  initHandshakeTimer()

  when(Closed) {

    // Transport layer events for outbound associations
    case Event(Status.Failure(e), OutboundUnassociated(_, statusPromise, _)) =>
      statusPromise.failure(e)
      stop()

    case Event(Handle(wrappedHandle), OutboundUnassociated(_, statusPromise, _)) =>
      wrappedHandle.readHandlerPromise.trySuccess(ActorHandleEventListener(self))
      if (sendAssociate(wrappedHandle, localHandshakeInfo)) {
        failureDetector.heartbeat()
        initHeartbeatTimer()
        goto(WaitHandshake).using(OutboundUnderlyingAssociated(statusPromise, wrappedHandle))

      } else {
        // Underlying transport was busy -- Associate could not be sent
        setTimer(
          "associate-retry",
          Handle(wrappedHandle),
          RARP(context.system).provider.remoteSettings.BackoffPeriod,
          repeat = false)
        stay()
      }

    case Event(DisassociateUnderlying(_), _) =>
      stop()

    case Event(HandshakeTimer, OutboundUnassociated(_, statusPromise, _)) =>
      val errMsg = "No response from remote for outbound association. Associate timed out after " +
        s"[${settings.HandshakeTimeout.toMillis} ms]."
      statusPromise.failure(new TimeoutException(errMsg))
      stop(FSM.Failure(TimeoutReason(errMsg)))

    case _ => stay()

  }

  // Timeout of this state is handled by the HandshakeTimer
  when(WaitHandshake) {
    case Event(Disassociated(info), _) =>
      stop(FSM.Failure(info))

    case Event(InboundPayload(p), OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) =>
      decodePdu(p) match {
        case Associate(handshakeInfo) if refuseUid.exists(_ == handshakeInfo.uid) =>
          sendDisassociate(wrappedHandle, Quarantined)
          stop(FSM.Failure(ForbiddenUidReason))

        case Associate(handshakeInfo) =>
          failureDetector.heartbeat()
          cancelTimer(handshakeTimerKey)
          goto(Open).using(
            AssociatedWaitHandler(
              notifyOutboundHandler(wrappedHandle, handshakeInfo, statusPromise),
              wrappedHandle,
              immutable.Queue.empty))

        case Disassociate(info) =>
          // After receiving Disassociate we MUST NOT send back a Disassociate (loop)
          stop(FSM.Failure(info))

        case msg =>
          // Expected handshake to be finished, dropping connection
          if (log.isDebugEnabled)
            log.debug(
              "Sending disassociate to [{}] because unexpected message of type [{}] was received during handshake",
              wrappedHandle,
              msg.getClass.getName)
          sendDisassociate(wrappedHandle, Unknown)
          stop()

      }

    case Event(HeartbeatTimer, OutboundUnderlyingAssociated(_, wrappedHandle)) => handleTimers(wrappedHandle)

    // Events for inbound associations
    case Event(InboundPayload(p), InboundUnassociated(associationHandler, wrappedHandle)) =>
      decodePdu(p) match {
        // After receiving Disassociate we MUST NOT send back a Disassociate (loop)
        case Disassociate(info) => stop(FSM.Failure(info))

        // Incoming association -- implicitly ACK by a heartbeat
        case Associate(info) =>
          if (!settings.RequireCookie || info.cookie == settings.SecureCookie) {
            sendAssociate(wrappedHandle, localHandshakeInfo)
            failureDetector.heartbeat()
            initHeartbeatTimer()
            cancelTimer(handshakeTimerKey)
            goto(Open).using(
              AssociatedWaitHandler(
                notifyInboundHandler(wrappedHandle, info, associationHandler),
                wrappedHandle,
                immutable.Queue.empty))
          } else {
            if (log.isDebugEnabled)
              log.warning(
                s"Association attempt with mismatching cookie from [{}]. Expected [{}] but received [{}].",
                info.origin,
                localHandshakeInfo.cookie.getOrElse(""),
                info.cookie.getOrElse(""))
            else
              markerLog.warning(
                LogMarker.Security,
                s"Association attempt with mismatching cookie from [{}].",
                info.origin)
            stop()
          }

        // Got a stray message -- explicitly reset the association (force remote endpoint to reassociate)
        case msg =>
          if (log.isDebugEnabled)
            log.debug(
              "Sending disassociate to [{}] because unexpected message of type [{}] was received while unassociated",
              wrappedHandle,
              msg.getClass.getName)
          sendDisassociate(wrappedHandle, Unknown)
          stop()

      }

    case Event(HandshakeTimer, OutboundUnderlyingAssociated(_, wrappedHandle)) =>
      if (log.isDebugEnabled)
        log.debug(
          "Sending disassociate to [{}] because handshake timed out for outbound association after [{}] ms.",
          wrappedHandle,
          settings.HandshakeTimeout.toMillis)

      sendDisassociate(wrappedHandle, Unknown)
      stop(
        FSM.Failure(TimeoutReason("No response from remote for outbound association. Handshake timed out after " +
        s"[${settings.HandshakeTimeout.toMillis} ms].")))

    case Event(HandshakeTimer, InboundUnassociated(_, wrappedHandle)) =>
      if (log.isDebugEnabled)
        log.debug(
          "Sending disassociate to [{}] because handshake timed out for inbound association after [{}] ms.",
          wrappedHandle,
          settings.HandshakeTimeout.toMillis)

      sendDisassociate(wrappedHandle, Unknown)
      stop(
        FSM.Failure(TimeoutReason("No response from remote for inbound association. Handshake timed out after " +
        s"[${settings.HandshakeTimeout.toMillis} ms].")))

  }

  when(Open) {
    case Event(Disassociated(info), _) =>
      stop(FSM.Failure(info))

    case Event(InboundPayload(p), _) =>
      decodePdu(p) match {
        case Disassociate(info) =>
          stop(FSM.Failure(info))

        case Heartbeat =>
          failureDetector.heartbeat()
          stay()

        case Payload(payload) =>
          // use incoming ordinary message as alive sign
          failureDetector.heartbeat()
          stateData match {
            case AssociatedWaitHandler(handlerFuture, wrappedHandle, queue) =>
              // Queue message until handler is registered
              stay().using(AssociatedWaitHandler(handlerFuture, wrappedHandle, queue :+ payload))
            case ListenerReady(listener, _) =>
              listener.notify(InboundPayload(payload))
              stay()
            case msg =>
              throw new AkkaProtocolException(
                s"unhandled message in state Open(InboundPayload) with type [${safeClassName(msg)}]")
          }

        case _ => stay()
      }

    case Event(HeartbeatTimer, AssociatedWaitHandler(_, wrappedHandle, _)) => handleTimers(wrappedHandle)
    case Event(HeartbeatTimer, ListenerReady(_, wrappedHandle))            => handleTimers(wrappedHandle)

    case Event(DisassociateUnderlying(info: DisassociateInfo), _) =>
      val handle = stateData match {
        case ListenerReady(_, wrappedHandle)            => wrappedHandle
        case AssociatedWaitHandler(_, wrappedHandle, _) => wrappedHandle
        case msg =>
          throw new AkkaProtocolException(
            s"unhandled message in state Open(DisassociateUnderlying) with type [${safeClassName(msg)}]")
      }
      // No debug logging here as sending DisassociateUnderlying(Unknown) should have been logged from where
      // it was sent

      sendDisassociate(handle, info)
      stop()

    case Event(HandleListenerRegistered(listener), AssociatedWaitHandler(_, wrappedHandle, queue)) =>
      queue.foreach { p =>
        listener.notify(InboundPayload(p))
      }
      stay().using(ListenerReady(listener, wrappedHandle))
  }

  private def initHeartbeatTimer(): Unit = {
    setTimer("heartbeat-timer", HeartbeatTimer, settings.TransportHeartBeatInterval, repeat = true)
  }

  private def initHandshakeTimer(): Unit = {
    setTimer(handshakeTimerKey, HandshakeTimer, settings.HandshakeTimeout, repeat = false)
  }

  private def handleTimers(wrappedHandle: AssociationHandle): State = {
    if (failureDetector.isAvailable) {
      sendHeartbeat(wrappedHandle)
      stay()
    } else {
      if (log.isDebugEnabled)
        log.debug(
          "Sending disassociate to [{}] because failure detector triggered in state [{}]",
          wrappedHandle,
          stateName)

      // send disassociate just to be sure
      sendDisassociate(wrappedHandle, Unknown)
      stop(
        FSM.Failure(TimeoutReason(s"No response from remote. " +
        s"Transport failure detector triggered. (internal state was $stateName)")))
    }
  }

  private def safeClassName(obj: AnyRef): String = obj match {
    case null => "null"
    case _    => obj.getClass.getName
  }

  override def postStop(): Unit = {
    cancelTimer("heartbeat-timer")
    super.postStop() // Pass to onTermination
  }

  onTermination {
    case StopEvent(reason, _, OutboundUnassociated(_, statusPromise, _)) =>
      statusPromise.tryFailure(reason match {
        case FSM.Failure(info: DisassociateInfo) => disassociateException(info)
        case _                                   => new AkkaProtocolException("Transport disassociated before handshake finished")
      })

    case StopEvent(reason, _, OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) =>
      statusPromise.tryFailure(reason match {
        case FSM.Failure(TimeoutReason(errorMessage)) =>
          new AkkaProtocolException(errorMessage)
        case FSM.Failure(info: DisassociateInfo) =>
          disassociateException(info)
        case FSM.Failure(ForbiddenUidReason) =>
          InvalidAssociationException("The remote system has a UID that has been quarantined. Association aborted.")
        case _ =>
          new AkkaProtocolException("Transport disassociated before handshake finished")
      })
      wrappedHandle.disassociate(disassociationReason(reason), log)

    case StopEvent(reason, _, AssociatedWaitHandler(handlerFuture, wrappedHandle, _)) =>
      // Invalidate exposed but still unfinished promise. The underlying association disappeared, so after
      // registration immediately signal a disassociate
      val disassociateNotification = reason match {
        case FSM.Failure(info: DisassociateInfo) => Disassociated(info)
        case _                                   => Disassociated(Unknown)
      }
      handlerFuture.foreach { _.notify(disassociateNotification) }
      wrappedHandle.disassociate(disassociationReason(reason), log)

    case StopEvent(reason, _, ListenerReady(handler, wrappedHandle)) =>
      val disassociateNotification = reason match {
        case FSM.Failure(info: DisassociateInfo) => Disassociated(info)
        case _                                   => Disassociated(Unknown)
      }
      handler.notify(disassociateNotification)
      wrappedHandle.disassociate(disassociationReason(reason), log)

    case StopEvent(reason, _, InboundUnassociated(_, wrappedHandle)) =>
      wrappedHandle.disassociate(disassociationReason(reason), log)

  }

  private def disassociateException(info: DisassociateInfo): Exception = info match {
    case Unknown =>
      new AkkaProtocolException("The remote system explicitly disassociated (reason unknown).")
    case Shutdown =>
      InvalidAssociationException("The remote system refused the association because it is shutting down.")
    case Quarantined =>
      InvalidAssociationException(
        "The remote system has quarantined this system. No further associations to the remote " +
        "system are possible until this system is restarted.")
  }

  override protected def logTermination(reason: FSM.Reason): Unit = reason match {
    case FSM.Failure(_: DisassociateInfo) => // no logging
    case FSM.Failure(ForbiddenUidReason)  => // no logging
    case FSM.Failure(TimeoutReason(errorMessage)) =>
      log.info(errorMessage)
    case _ => super.logTermination(reason)
  }

  private def listenForListenerRegistration(readHandlerPromise: Promise[HandleEventListener]): Unit =
    readHandlerPromise.future.map { HandleListenerRegistered(_) }.pipeTo(self)

  private def notifyOutboundHandler(
      wrappedHandle: AssociationHandle,
      handshakeInfo: HandshakeInfo,
      statusPromise: Promise[AssociationHandle]): Future[HandleEventListener] = {
    val readHandlerPromise = Promise[HandleEventListener]()
    listenForListenerRegistration(readHandlerPromise)

    statusPromise.success(
      new AkkaProtocolHandle(
        localAddress,
        wrappedHandle.remoteAddress,
        readHandlerPromise,
        wrappedHandle,
        handshakeInfo,
        self,
        codec))
    readHandlerPromise.future
  }

  private def notifyInboundHandler(
      wrappedHandle: AssociationHandle,
      handshakeInfo: HandshakeInfo,
      associationListener: AssociationEventListener): Future[HandleEventListener] = {
    val readHandlerPromise = Promise[HandleEventListener]()
    listenForListenerRegistration(readHandlerPromise)

    associationListener.notify(
      InboundAssociation(
        new AkkaProtocolHandle(
          localAddress,
          handshakeInfo.origin,
          readHandlerPromise,
          wrappedHandle,
          handshakeInfo,
          self,
          codec)))
    readHandlerPromise.future
  }

  private def decodePdu(pdu: ByteString): AkkaPdu =
    try codec.decodePdu(pdu)
    catch {
      case NonFatal(e) =>
        throw new AkkaProtocolException("Error while decoding incoming Akka PDU of length: " + pdu.length, e)
    }

  // Neither heartbeats neither disassociate cares about backing off if write fails:
  //  - Missing heartbeats are not critical
  //  - Disassociate messages are not guaranteed anyway
  private def sendHeartbeat(wrappedHandle: AssociationHandle): Boolean =
    try wrappedHandle.write(codec.constructHeartbeat)
    catch {
      case NonFatal(e) => throw new AkkaProtocolException("Error writing HEARTBEAT to transport", e)
    }

  private def sendDisassociate(wrappedHandle: AssociationHandle, info: DisassociateInfo): Unit =
    try wrappedHandle.write(codec.constructDisassociate(info))
    catch {
      case NonFatal(e) => throw new AkkaProtocolException("Error writing DISASSOCIATE to transport", e)
    }

  private def sendAssociate(wrappedHandle: AssociationHandle, info: HandshakeInfo): Boolean =
    try {
      wrappedHandle.write(codec.constructAssociate(info))
    } catch {
      case NonFatal(e) => throw new AkkaProtocolException("Error writing ASSOCIATE to transport", e)
    }

  private def disassociationReason(reason: FSM.Reason): String = reason match {
    case FSM.Normal      => "the ProtocolStateActor was stopped normally"
    case FSM.Shutdown    => "the ProtocolStateActor was shutdown"
    case FSM.Failure(ex) => s"the ProtocolStateActor failed: $ex"
  }
}
