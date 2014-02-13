/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import akka.actor.Terminated
import akka.actor._
import akka.dispatch.sysmsg.SystemMessage
import akka.event.{ Logging, LoggingAdapter }
import akka.pattern.pipe
import akka.remote.EndpointManager.{ ResendState, Link, Send }
import akka.remote.EndpointWriter.{ StoppedReading, FlushAndStop }
import akka.remote.WireFormats.SerializedMessage
import akka.remote.transport.AkkaPduCodec.Message
import akka.remote.transport.AssociationHandle.{ DisassociateInfo, ActorHandleEventListener, Disassociated, InboundPayload }
import akka.remote.transport.Transport.InvalidAssociationException
import akka.remote.transport._
import akka.serialization.Serialization
import akka.util.ByteString
import akka.{ OnlyCauseStackTrace, AkkaException }
import java.io.NotSerializableException
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec
import scala.concurrent.duration.{ Duration, Deadline }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[remote] trait InboundMessageDispatcher {
  def dispatch(recipient: InternalActorRef,
               recipientAddress: Address,
               serializedMessage: SerializedMessage,
               senderOption: Option[ActorRef]): Unit
}

/**
 * INTERNAL API
 */
private[remote] class DefaultMessageDispatcher(private val system: ExtendedActorSystem,
                                               private val provider: RemoteActorRefProvider,
                                               private val log: LoggingAdapter) extends InboundMessageDispatcher {

  private val remoteDaemon = provider.remoteDaemon

  override def dispatch(recipient: InternalActorRef,
                        recipientAddress: Address,
                        serializedMessage: SerializedMessage,
                        senderOption: Option[ActorRef]): Unit = {

    import provider.remoteSettings._

    lazy val payload: AnyRef = MessageSerializer.deserialize(system, serializedMessage)
    def payloadClass: Class[_] = if (payload eq null) null else payload.getClass
    val sender: ActorRef = senderOption.getOrElse(system.deadLetters)
    val originalReceiver = recipient.path

    def msgLog = s"RemoteMessage: [$payload] to [$recipient]<+[$originalReceiver] from [$sender()]"

    recipient match {

      case `remoteDaemon` ⇒
        if (UntrustedMode) log.debug("dropping daemon message in untrusted mode")
        else {
          if (LogReceive) log.debug("received daemon message {}", msgLog)
          remoteDaemon ! payload
        }

      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (LogReceive) log.debug("received local message {}", msgLog)
        payload match {
          case sel: ActorSelectionMessage ⇒
            if (UntrustedMode && (!TrustedSelectionPaths.contains(sel.elements.mkString("/", "/", "")) ||
              sel.msg.isInstanceOf[PossiblyHarmful] || l != provider.rootGuardian))
              log.debug("operating in UntrustedMode, dropping inbound actor selection to [{}], " +
                "allow it by adding the path to 'akka.remote.trusted-selection-paths' configuration",
                sel.elements.mkString("/", "/", ""))
            else
              // run the receive logic for ActorSelectionMessage here to make sure it is not stuck on busy user actor
              ActorSelection.deliverSelection(l, sender, sel)
          case msg: PossiblyHarmful if UntrustedMode ⇒
            log.debug("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type [{}]", msg.getClass.getName)
          case msg: SystemMessage ⇒ l.sendSystemMessage(msg)
          case msg                ⇒ l.!(msg)(sender)
        }

      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal && !UntrustedMode ⇒
        if (LogReceive) log.debug("received remote-destined message {}", msgLog)
        if (provider.transport.addresses(recipientAddress))
          // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
          r.!(payload)(sender)
        else
          log.error("dropping message [{}] for non-local recipient [{}] arriving at [{}] inbound addresses are [{}]",
            payloadClass, r, recipientAddress, provider.transport.addresses.mkString(", "))

      case r ⇒ log.error("dropping message [{}] for unknown recipient [{}] arriving at [{}] inbound addresses are [{}]",
        payloadClass, r, recipientAddress, provider.transport.addresses.mkString(", "))

    }
  }

}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class EndpointException(msg: String, cause: Throwable) extends AkkaException(msg, cause) with OnlyCauseStackTrace {
  def this(msg: String) = this(msg, null)
}

/**
 * INTERNAL API
 */
private[remote] trait AssociationProblem

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] case class ShutDownAssociation(localAddress: Address, remoteAddress: Address, cause: Throwable)
  extends EndpointException("Shut down address: " + remoteAddress, cause) with AssociationProblem

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] case class InvalidAssociation(localAddress: Address, remoteAddress: Address, cause: Throwable)
  extends EndpointException("Invalid address: " + remoteAddress, cause) with AssociationProblem

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] case class HopelessAssociation(localAddress: Address, remoteAddress: Address, uid: Option[Int], cause: Throwable)
  extends EndpointException("Catastrophic association error.") with AssociationProblem

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class EndpointDisassociatedException(msg: String) extends EndpointException(msg)

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class EndpointAssociationException(msg: String, cause: Throwable) extends EndpointException(msg, cause)

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] class OversizedPayloadException(msg: String) extends EndpointException(msg)

/**
 * INTERNAL API
 */
private[remote] object ReliableDeliverySupervisor {
  case object Ungate
  case object AttemptSysMsgRedelivery
  case class GotUid(uid: Int)

  def props(
    handleOrActive: Option[AkkaProtocolHandle],
    localAddress: Address,
    remoteAddress: Address,
    refuseUid: Option[Int],
    transport: AkkaProtocolTransport,
    settings: RemoteSettings,
    codec: AkkaPduCodec,
    receiveBuffers: ConcurrentHashMap[Link, ResendState]): Props =
    Props(classOf[ReliableDeliverySupervisor], handleOrActive, localAddress, remoteAddress, refuseUid, transport, settings,
      codec, receiveBuffers)
}

/**
 * INTERNAL API
 */
private[remote] class ReliableDeliverySupervisor(
  handleOrActive: Option[AkkaProtocolHandle],
  val localAddress: Address,
  val remoteAddress: Address,
  val refuseUid: Option[Int],
  val transport: AkkaProtocolTransport,
  val settings: RemoteSettings,
  val codec: AkkaPduCodec,
  val receiveBuffers: ConcurrentHashMap[Link, ResendState]) extends Actor with ActorLogging {
  import ReliableDeliverySupervisor._
  import context.dispatcher

  var autoResendTimer: Option[Cancellable] = None

  def scheduleAutoResend(): Unit = if (resendBuffer.nacked.nonEmpty || resendBuffer.nonAcked.nonEmpty) {
    if (autoResendTimer.isEmpty)
      autoResendTimer = Some(context.system.scheduler.scheduleOnce(settings.SysResendTimeout, self, AttemptSysMsgRedelivery))
  }

  def rescheduleAutoResend(): Unit = {
    autoResendTimer.foreach(_.cancel())
    autoResendTimer = None
    scheduleAutoResend()
  }

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e @ (_: AssociationProblem) ⇒ Escalate
    case NonFatal(e) ⇒
      log.warning("Association with remote system [{}] has failed, address is now gated for [{}] ms. Reason is: [{}].",
        remoteAddress, settings.RetryGateClosedFor.toMillis, e.getMessage)
      uidConfirmed = false // Need confirmation of UID again
      context.become(gated)
      currentHandle = None
      context.parent ! StoppedReading(self)
      Stop
  }

  var currentHandle: Option[AkkaProtocolHandle] = handleOrActive

  var resendBuffer: AckedSendBuffer[Send] = _
  var lastCumulativeAck: SeqNo = _
  var seqCounter: Long = _
  var pendingAcks = Vector.empty[Ack]

  def reset() {
    resendBuffer = new AckedSendBuffer[Send](settings.SysMsgBufferSize)
    scheduleAutoResend()
    lastCumulativeAck = SeqNo(-1)
    seqCounter = 0L
    pendingAcks = Vector.empty
  }

  reset()

  def nextSeq(): SeqNo = {
    val tmp = seqCounter
    seqCounter += 1
    SeqNo(tmp)
  }

  var writer: ActorRef = createWriter()
  var uid: Option[Int] = handleOrActive map { _.handshakeInfo.uid }
  // Processing of Acks has to be delayed until the UID after a reconnect is discovered. Depending whether the
  // UID matches the expected one, pending Acks can be processed, or must be dropped. It is guaranteed that for
  // any inbound connections (calling createWriter()) the first message from that connection is GotUid() therefore
  // it serves a separator.
  // If we already have an inbound handle then UID is initially confirmed.
  // (This actor is never restarted)
  var uidConfirmed: Boolean = uid.isDefined

  def unstashAcks(): Unit = {
    pendingAcks foreach (self ! _)
    pendingAcks = Vector.empty
  }

  override def postStop(): Unit = {
    // All remaining messages in the buffer has to be delivered to dead letters. It is important to clear the sequence
    // number otherwise deadLetters will ignore it to avoid reporting system messages as dead letters while they are
    // still possibly retransmitted.
    // Such a situation may arise when the EndpointWriter is shut down, and all of its mailbox contents are delivered
    // to dead letters. These messages should be ignored, as they still live in resendBuffer and might be delivered to
    // the remote system later.
    (resendBuffer.nacked ++ resendBuffer.nonAcked) foreach { s ⇒ context.system.deadLetters ! s.copy(seqOpt = None) }
    receiveBuffers.remove(Link(localAddress, remoteAddress))
  }

  override def postRestart(reason: Throwable): Unit = {
    throw new IllegalStateException(
      "BUG: ReliableDeliverySupervisor has been attempted to be restarted. This must not happen.")
  }

  override def receive: Receive = {
    case FlushAndStop ⇒
      // Trying to serve until our last breath
      resendAll()
      writer ! FlushAndStop
      context.become(flushWait)
    case s: Send ⇒
      handleSend(s)
    case ack: Ack ⇒
      if (!uidConfirmed) pendingAcks = pendingAcks :+ ack
      else {
        try resendBuffer = resendBuffer.acknowledge(ack)
        catch {
          case NonFatal(e) ⇒
            throw new InvalidAssociationException(s"Error encountered while processing system message acknowledgement $resendBuffer $ack", e)
        }

        if (lastCumulativeAck < ack.cumulativeAck) {
          lastCumulativeAck = ack.cumulativeAck
          // Cumulative ack is progressing, we might not need to resend non-acked messages yet.
          // If this progression stops, the timer will eventually kick in, since scheduleAutoResend
          // does not cancel existing timers (see the "else" case).
          rescheduleAutoResend()
        } else scheduleAutoResend()

        resendNacked()
      }
    case AttemptSysMsgRedelivery ⇒
      if (uidConfirmed) resendAll()
    case Terminated(_) ⇒
      currentHandle = None
      context.parent ! StoppedReading(self)
      if (resendBuffer.nonAcked.nonEmpty || resendBuffer.nacked.nonEmpty)
        context.system.scheduler.scheduleOnce(settings.SysResendTimeout, self, AttemptSysMsgRedelivery)
      context.become(idle)
    case GotUid(receivedUid) ⇒
      // New system that has the same address as the old - need to start from fresh state
      uidConfirmed = true
      if (uid.exists(_ != receivedUid)) reset()
      else unstashAcks()
      uid = Some(receivedUid)
      resendAll()

    case s: EndpointWriter.StopReading ⇒ writer forward s
  }

  def gated: Receive = {
    case Terminated(_) ⇒
      context.system.scheduler.scheduleOnce(settings.RetryGateClosedFor, self, Ungate)
    case Ungate ⇒
      if (resendBuffer.nonAcked.nonEmpty || resendBuffer.nacked.nonEmpty) {
        writer = createWriter()
        // Resending will be triggered by the incoming GotUid message after the connection finished
        context.become(receive)
      } else context.become(idle)
    case s @ Send(msg: SystemMessage, _, _, _) ⇒ tryBuffer(s.copy(seqOpt = Some(nextSeq())))
    case s: Send                               ⇒ context.system.deadLetters ! s
    case EndpointWriter.FlushAndStop           ⇒ context.stop(self)
    case EndpointWriter.StopReading(w)         ⇒ sender() ! EndpointWriter.StoppedReading(w)
    case _                                     ⇒ // Ignore
  }

  def idle: Receive = {
    case s: Send ⇒
      writer = createWriter()
      // Resending will be triggered by the incoming GotUid message after the connection finished
      handleSend(s)
      context.become(receive)
    case AttemptSysMsgRedelivery ⇒
      writer = createWriter()
      // Resending will be triggered by the incoming GotUid message after the connection finished
      context.become(receive)
    case EndpointWriter.FlushAndStop   ⇒ context.stop(self)
    case EndpointWriter.StopReading(w) ⇒ sender() ! EndpointWriter.StoppedReading(w)
  }

  def flushWait: Receive = {
    case Terminated(_) ⇒
      // Clear buffer to prevent sending system messages to dead letters -- at this point we are shutting down
      // and don't really know if they were properly delivered or not.
      resendBuffer = new AckedSendBuffer[Send](0)
      context.stop(self)
    case _ ⇒ // Ignore
  }

  private def handleSend(send: Send): Unit =
    if (send.message.isInstanceOf[SystemMessage]) {
      val sequencedSend = send.copy(seqOpt = Some(nextSeq()))
      tryBuffer(sequencedSend)
      // If we have not confirmed the remote UID we cannot transfer the system message at this point just buffer it.
      // GotUid will kick resendAll() causing the messages to be properly written
      if (uidConfirmed) writer ! sequencedSend
    } else writer ! send

  private def resendNacked(): Unit = resendBuffer.nacked foreach { writer ! _ }

  private def resendAll(): Unit = {
    resendNacked()
    resendBuffer.nonAcked foreach { writer ! _ }
    rescheduleAutoResend()
  }

  private def tryBuffer(s: Send): Unit =
    try {
      resendBuffer = resendBuffer buffer s
    } catch {
      case NonFatal(e) ⇒ throw new HopelessAssociation(localAddress, remoteAddress, uid, e)
    }

  private def createWriter(): ActorRef = {
    context.watch(context.actorOf(RARP(context.system).configureDispatcher(EndpointWriter.props(
      handleOrActive = currentHandle,
      localAddress = localAddress,
      remoteAddress = remoteAddress,
      refuseUid,
      transport = transport,
      settings = settings,
      AkkaPduProtobufCodec,
      receiveBuffers = receiveBuffers,
      reliableDeliverySupervisor = Some(self))).withDeploy(Deploy.local), "endpointWriter"))
  }
}

/**
 * INTERNAL API
 */
private[remote] abstract class EndpointActor(
  val localAddress: Address,
  val remoteAddress: Address,
  val transport: Transport,
  val settings: RemoteSettings,
  val codec: AkkaPduCodec) extends Actor with ActorLogging {

  def inbound: Boolean

  val eventPublisher = new EventPublisher(context.system, log, settings.RemoteLifecycleEventsLogLevel)

  def publishError(reason: Throwable, logLevel: Logging.LogLevel): Unit =
    tryPublish(AssociationErrorEvent(reason, localAddress, remoteAddress, inbound, logLevel))

  def publishDisassociated(): Unit = tryPublish(DisassociatedEvent(localAddress, remoteAddress, inbound))

  private def tryPublish(ev: AssociationEvent): Unit = try
    eventPublisher.notifyListeners(ev)
  catch { case NonFatal(e) ⇒ log.error(e, "Unable to publish error event to EventStream.") }
}

/**
 * INTERNAL API
 */
private[remote] object EndpointWriter {

  def props(
    handleOrActive: Option[AkkaProtocolHandle],
    localAddress: Address,
    remoteAddress: Address,
    refuseUid: Option[Int],
    transport: AkkaProtocolTransport,
    settings: RemoteSettings,
    codec: AkkaPduCodec,
    receiveBuffers: ConcurrentHashMap[Link, ResendState],
    reliableDeliverySupervisor: Option[ActorRef]): Props =
    Props(classOf[EndpointWriter], handleOrActive, localAddress, remoteAddress, refuseUid, transport, settings, codec,
      receiveBuffers, reliableDeliverySupervisor)

  /**
   * This message signals that the current association maintained by the local EndpointWriter and EndpointReader is
   * to be overridden by a new inbound association. This is needed to avoid parallel inbound associations from the
   * same remote endpoint: when a parallel inbound association is detected, the old one is removed and the new one is
   * used instead.
   * @param handle Handle of the new inbound association.
   */
  case class TakeOver(handle: AkkaProtocolHandle) extends NoSerializationVerificationNeeded
  case class TookOver(writer: ActorRef, handle: AkkaProtocolHandle) extends NoSerializationVerificationNeeded
  case object BackoffTimer
  case object FlushAndStop
  case object AckIdleCheckTimer
  case class StopReading(writer: ActorRef)
  case class StoppedReading(writer: ActorRef)

  case class Handle(handle: AkkaProtocolHandle) extends NoSerializationVerificationNeeded

  case class OutboundAck(ack: Ack)

  sealed trait State
  case object Initializing extends State
  case object Buffering extends State
  case object Writing extends State
  case object Handoff extends State

  val AckIdleTimerName = "AckIdleTimer"
}

/**
 * INTERNAL API
 */
private[remote] class EndpointWriter(
  handleOrActive: Option[AkkaProtocolHandle],
  localAddress: Address,
  remoteAddress: Address,
  refuseUid: Option[Int],
  transport: AkkaProtocolTransport,
  settings: RemoteSettings,
  codec: AkkaPduCodec,
  val receiveBuffers: ConcurrentHashMap[Link, ResendState],
  val reliableDeliverySupervisor: Option[ActorRef])
  extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) with UnboundedStash
  with FSM[EndpointWriter.State, Unit] {

  import EndpointWriter._
  import context.dispatcher

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val remoteMetrics = RemoteMetricsExtension(extendedSystem)

  var reader: Option[ActorRef] = None
  var handle: Option[AkkaProtocolHandle] = handleOrActive // FIXME: refactor into state data
  val readerId = Iterator from 0

  def newAckDeadline: Deadline = Deadline.now + settings.SysMsgAckTimeout
  var ackDeadline: Deadline = newAckDeadline

  var lastAck: Option[Ack] = None

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case NonFatal(e) ⇒ publishAndThrow(e, Logging.ErrorLevel)
  }

  val provider = RARP(extendedSystem).provider
  val msgDispatch = new DefaultMessageDispatcher(extendedSystem, provider, log)

  var inbound = handle.isDefined
  var stopReason: DisassociateInfo = AssociationHandle.Unknown

  private def publishAndThrow(reason: Throwable, logLevel: Logging.LogLevel): Nothing = {
    reason match {
      case _: EndpointDisassociatedException ⇒ publishDisassociated()
      case _                                 ⇒ publishError(reason, logLevel)
    }
    throw reason
  }

  private def logAndStay(reason: Throwable): State = {
    log.error(reason, "Transient association error (association remains live)")
    stay()
  }

  override def postRestart(reason: Throwable): Unit = {
    handle = None // Wipe out the possibly injected handle
    inbound = false
    preStart()
  }

  override def preStart(): Unit = {

    setTimer(AckIdleTimerName, AckIdleCheckTimer, settings.SysMsgAckTimeout / 2, repeat = true)

    startWith(
      handle match {
        case Some(h) ⇒
          reader = startReadEndpoint(h)
          Writing
        case None ⇒
          transport.associate(remoteAddress, refuseUid).map(Handle(_)) pipeTo self
          Initializing
      },
      stateData = ())
  }

  when(Initializing) {
    case Event(Send(msg, senderOption, recipient, _), _) ⇒
      stash()
      stay()
    case Event(Status.Failure(e: InvalidAssociationException), _) ⇒
      publishAndThrow(new InvalidAssociation(localAddress, remoteAddress, e), Logging.WarningLevel)
    case Event(Status.Failure(e), _) ⇒
      publishAndThrow(new EndpointAssociationException(s"Association failed with [$remoteAddress]", e), Logging.DebugLevel)
    case Event(Handle(inboundHandle), _) ⇒
      // Assert handle == None?
      context.parent ! ReliableDeliverySupervisor.GotUid(inboundHandle.handshakeInfo.uid)
      handle = Some(inboundHandle)
      reader = startReadEndpoint(inboundHandle)
      goto(Writing)

  }

  when(Buffering) {
    case Event(_: Send, _) ⇒
      stash()
      stay()

    case Event(BackoffTimer, _) ⇒ goto(Writing)

    case Event(FlushAndStop, _) ⇒
      stash() // Flushing is postponed after the pending writes
      stay()
  }

  when(Writing) {
    case Event(s @ Send(msg, senderOption, recipient, seqOption), _) ⇒
      try {
        handle match {
          case Some(h) ⇒
            if (provider.remoteSettings.LogSend) {
              def msgLog = s"RemoteMessage: [$msg] to [$recipient]<+[${recipient.path}] from [${senderOption.getOrElse(extendedSystem.deadLetters)}]"
              log.debug("sending message {}", msgLog)
            }

            val pdu = codec.constructMessage(
              recipient.localAddressToUse,
              recipient,
              serializeMessage(msg),
              senderOption,
              seqOption = seqOption,
              ackOption = lastAck)

            ackDeadline = newAckDeadline
            lastAck = None

            val pduSize = pdu.size
            remoteMetrics.logPayloadBytes(msg, pduSize)

            if (pduSize > transport.maximumPayloadBytes) {
              logAndStay(new OversizedPayloadException(s"Discarding oversized payload sent to ${recipient}: max allowed size ${transport.maximumPayloadBytes} bytes, actual size of encoded ${msg.getClass} was ${pdu.size} bytes."))
            } else if (h.write(pdu)) {
              stay()
            } else {
              if (seqOption.isEmpty) stash()
              goto(Buffering)
            }
          case None ⇒
            throw new EndpointException("Internal error: Endpoint is in state Writing, but no association handle is present.")
        }
      } catch {
        case e: NotSerializableException ⇒
          logAndStay(e)
        case e: EndpointException ⇒
          publishAndThrow(e, Logging.ErrorLevel)
        case NonFatal(e) ⇒
          publishAndThrow(new EndpointException("Failed to write message to the transport", e), Logging.ErrorLevel)
      }

    // We are in Writing state, so stash is empty, safe to stop here
    case Event(FlushAndStop, _) ⇒
      // Try to send a last Ack message
      trySendPureAck()
      stopReason = AssociationHandle.Shutdown
      stop()

    case Event(AckIdleCheckTimer, _) if ackDeadline.isOverdue() ⇒
      trySendPureAck()
      stay()
  }

  when(Handoff) {
    case Event(Terminated(_), _) ⇒
      reader = startReadEndpoint(handle.get)
      unstashAll()
      goto(Writing)

    case _ ⇒
      stash()
      stay()

  }

  whenUnhandled {
    case Event(Terminated(r), _) if r == reader.orNull ⇒
      publishAndThrow(new EndpointDisassociatedException("Disassociated"), Logging.DebugLevel)
    case Event(s: StopReading, _) ⇒
      reader match {
        case Some(r) ⇒ r forward s
        case None    ⇒ stash()
      }
      stay()
    case Event(TakeOver(newHandle), _) ⇒
      // Shutdown old reader
      handle foreach { _.disassociate() }
      handle = Some(newHandle)
      sender() ! TookOver(self, newHandle)
      goto(Handoff)
    case Event(FlushAndStop, _) ⇒
      stopReason = AssociationHandle.Shutdown
      stop()
    case Event(OutboundAck(ack), _) ⇒
      lastAck = Some(ack)
      stay()
    case Event(AckIdleCheckTimer, _) ⇒ stay() // Ignore
  }

  onTransition {
    case Initializing -> Writing ⇒
      unstashAll()
      eventPublisher.notifyListeners(AssociatedEvent(localAddress, remoteAddress, inbound))
    case Writing -> Buffering ⇒
      setTimer("backoff-timer", BackoffTimer, settings.BackoffPeriod, repeat = false)
    case Buffering -> Writing ⇒
      unstashAll()
      cancelTimer("backoff-timer")
  }

  onTermination {
    case StopEvent(_, _, _) ⇒
      cancelTimer(AckIdleTimerName)
      // It is important to call unstashAll() for the stash to work properly and maintain messages during restart.
      // As the FSM trait does not call super.postStop(), this call is needed
      unstashAll()
      handle foreach { _.disassociate(stopReason) }
      eventPublisher.notifyListeners(DisassociatedEvent(localAddress, remoteAddress, inbound))
  }

  private def trySendPureAck(): Unit = for (h ← handle; ack ← lastAck)
    if (h.write(codec.constructPureAck(ack))) {
      ackDeadline = newAckDeadline
      lastAck = None
    }

  private def startReadEndpoint(handle: AkkaProtocolHandle): Some[ActorRef] = {
    val newReader =
      context.watch(context.actorOf(
        RARP(context.system).configureDispatcher(EndpointReader.props(localAddress, remoteAddress, transport, settings, codec,
          msgDispatch, inbound, handle.handshakeInfo.uid, reliableDeliverySupervisor, receiveBuffers)).withDeploy(Deploy.local),
        "endpointReader-" + AddressUrlEncoder(remoteAddress) + "-" + readerId.next()))
    handle.readHandlerPromise.success(ActorHandleEventListener(newReader))
    Some(newReader)
  }

  private def serializeMessage(msg: Any): SerializedMessage = handle match {
    case Some(h) ⇒
      Serialization.currentTransportInformation.withValue(Serialization.Information(h.localAddress, extendedSystem)) {
        (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
      }
    case None ⇒
      throw new EndpointException("Internal error: No handle was present during serialization of outbound message.")
  }

}

/**
 * INTERNAL API
 */
private[remote] object EndpointReader {

  def props(
    localAddress: Address,
    remoteAddress: Address,
    transport: Transport,
    settings: RemoteSettings,
    codec: AkkaPduCodec,
    msgDispatch: InboundMessageDispatcher,
    inbound: Boolean,
    uid: Int,
    reliableDeliverySupervisor: Option[ActorRef],
    receiveBuffers: ConcurrentHashMap[Link, ResendState]): Props =
    Props(classOf[EndpointReader], localAddress, remoteAddress, transport, settings, codec, msgDispatch, inbound,
      uid, reliableDeliverySupervisor, receiveBuffers)

}

/**
 * INTERNAL API
 */
private[remote] class EndpointReader(
  localAddress: Address,
  remoteAddress: Address,
  transport: Transport,
  settings: RemoteSettings,
  codec: AkkaPduCodec,
  msgDispatch: InboundMessageDispatcher,
  val inbound: Boolean,
  val uid: Int,
  val reliableDeliverySupervisor: Option[ActorRef],
  val receiveBuffers: ConcurrentHashMap[Link, ResendState]) extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) {

  import EndpointWriter.{ OutboundAck, StopReading, StoppedReading }

  val provider = RARP(context.system).provider
  var ackedReceiveBuffer = new AckedReceiveBuffer[Message]

  override def preStart(): Unit = {
    receiveBuffers.get(Link(localAddress, remoteAddress)) match {
      case null ⇒
      case ResendState(`uid`, buffer) ⇒
        ackedReceiveBuffer = buffer
        deliverAndAck()
      case _ ⇒
    }
  }

  override def postStop(): Unit = saveState()

  def saveState(): Unit = {
    def merge(currentState: ResendState, oldState: ResendState): ResendState =
      if (currentState.uid == oldState.uid) ResendState(uid, oldState.buffer.mergeFrom(currentState.buffer))
      else currentState

    @tailrec
    def updateSavedState(key: Link, expectedState: ResendState): Unit = {
      if (expectedState eq null) {
        if (receiveBuffers.putIfAbsent(key, ResendState(uid, ackedReceiveBuffer)) ne null)
          updateSavedState(key, receiveBuffers.get(key))
      } else if (!receiveBuffers.replace(key, expectedState, merge(ResendState(uid, ackedReceiveBuffer), expectedState)))
        updateSavedState(key, receiveBuffers.get(key))
    }

    val key = Link(localAddress, remoteAddress)
    updateSavedState(key, receiveBuffers.get(key))
  }

  override def receive: Receive = {
    case Disassociated(info) ⇒ handleDisassociated(info)

    case InboundPayload(p) if p.size <= transport.maximumPayloadBytes ⇒
      val (ackOption, msgOption) = tryDecodeMessageAndAck(p)

      for (ack ← ackOption; reliableDelivery ← reliableDeliverySupervisor) reliableDelivery ! ack

      msgOption match {
        case Some(msg) ⇒
          if (msg.reliableDeliveryEnabled) {
            ackedReceiveBuffer = ackedReceiveBuffer.receive(msg)
            deliverAndAck()
          } else msgDispatch.dispatch(msg.recipient, msg.recipientAddress, msg.serializedMessage, msg.senderOption)

        case None ⇒
      }

    case InboundPayload(oversized) ⇒
      log.error(new OversizedPayloadException(s"Discarding oversized payload received: " +
        s"max allowed size [${transport.maximumPayloadBytes}] bytes, actual size [${oversized.size}] bytes."),
        "Transient error while reading from association (association remains live)")

    case StopReading(writer) ⇒
      saveState()
      context.become(notReading)
      sender() ! StoppedReading(writer)

  }

  def notReading: Receive = {
    case Disassociated(info) ⇒ handleDisassociated(info)

    case StopReading(writer) ⇒
      sender() ! StoppedReading(writer)

    case InboundPayload(p) ⇒
      val (ackOption, _) = tryDecodeMessageAndAck(p)
      for (ack ← ackOption; reliableDelivery ← reliableDeliverySupervisor) reliableDelivery ! ack

    case _ ⇒
  }

  private def handleDisassociated(info: DisassociateInfo): Unit = info match {
    case AssociationHandle.Unknown ⇒
      context.stop(self)
    case AssociationHandle.Shutdown ⇒
      throw ShutDownAssociation(
        localAddress,
        remoteAddress,
        InvalidAssociationException("The remote system terminated the association because it is shutting down."))
    case AssociationHandle.Quarantined ⇒
      throw InvalidAssociation(
        localAddress,
        remoteAddress,
        InvalidAssociationException("The remote system has quarantined this system. No further associations " +
          "to the remote system are possible until this system is restarted."))
  }

  private def deliverAndAck(): Unit = {
    val (updatedBuffer, deliver, ack) = ackedReceiveBuffer.extractDeliverable
    ackedReceiveBuffer = updatedBuffer

    // Notify writer that some messages can be acked
    context.parent ! OutboundAck(ack)
    deliver foreach { m ⇒
      msgDispatch.dispatch(m.recipient, m.recipientAddress, m.serializedMessage, m.senderOption)
    }
  }

  private def tryDecodeMessageAndAck(pdu: ByteString): (Option[Ack], Option[Message]) = try {
    codec.decodeMessage(pdu, provider, localAddress)
  } catch {
    case NonFatal(e) ⇒ throw new EndpointException("Error while decoding incoming Akka PDU", e)
  }
}
