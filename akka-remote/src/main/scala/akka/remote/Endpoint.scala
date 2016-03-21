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
import java.util.concurrent.{ TimeUnit, TimeoutException, ConcurrentHashMap }
import scala.annotation.tailrec
import scala.concurrent.duration.{ Duration, Deadline }
import scala.util.control.NonFatal
import java.util.concurrent.locks.LockSupport
import scala.concurrent.Future
import scala.concurrent.blocking

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
  case class GotUid(uid: Int, remoteAddres: Address)

  case object IsIdle
  case object Idle
  case object TooLongIdle

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

  val autoResendTimer = context.system.scheduler.schedule(
    settings.SysResendTimeout, settings.SysResendTimeout, self, AttemptSysMsgRedelivery)

  private var bufferWasInUse = false

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case e @ (_: AssociationProblem) ⇒ Escalate
    case NonFatal(e) ⇒
      val causedBy = if (e.getCause == null) "" else s"Caused by: [${e.getCause.getMessage}]"
      log.warning("Association with remote system [{}] has failed, address is now gated for [{}] ms. Reason: [{}] {}",
        remoteAddress, settings.RetryGateClosedFor.toMillis, e.getMessage, causedBy)
      uidConfirmed = false // Need confirmation of UID again
      if (bufferWasInUse) {
        if ((resendBuffer.nacked.nonEmpty || resendBuffer.nonAcked.nonEmpty) && bailoutAt.isEmpty)
          bailoutAt = Some(Deadline.now + settings.InitialSysMsgDeliveryTimeout)
        context.become(gated(writerTerminated = false, earlyUngateRequested = false))
        currentHandle = None
        context.parent ! StoppedReading(self)
        Stop
      } else Escalate
  }

  var currentHandle: Option[AkkaProtocolHandle] = handleOrActive

  var resendBuffer: AckedSendBuffer[Send] = _
  var seqCounter: Long = _

  def reset(): Unit = {
    resendBuffer = new AckedSendBuffer[Send](settings.SysMsgBufferSize)
    bufferWasInUse = false
    seqCounter = 0L
    bailoutAt = None
  }

  reset()

  def nextSeq(): SeqNo = {
    val tmp = seqCounter
    seqCounter += 1
    SeqNo(tmp)
  }

  var writer: ActorRef = createWriter()
  var uid: Option[Int] = handleOrActive map { _.handshakeInfo.uid }
  var bailoutAt: Option[Deadline] = None
  var maxSilenceTimer: Option[Cancellable] = None
  // Processing of Acks has to be delayed until the UID after a reconnect is discovered. Depending whether the
  // UID matches the expected one, pending Acks can be processed, or must be dropped. It is guaranteed that for
  // any inbound connections (calling createWriter()) the first message from that connection is GotUid() therefore
  // it serves a separator.
  // If we already have an inbound handle then UID is initially confirmed.
  // (This actor is never restarted)
  var uidConfirmed: Boolean = uid.isDefined

  override def postStop(): Unit = {
    // All remaining messages in the buffer has to be delivered to dead letters. It is important to clear the sequence
    // number otherwise deadLetters will ignore it to avoid reporting system messages as dead letters while they are
    // still possibly retransmitted.
    // Such a situation may arise when the EndpointWriter is shut down, and all of its mailbox contents are delivered
    // to dead letters. These messages should be ignored, as they still live in resendBuffer and might be delivered to
    // the remote system later.
    (resendBuffer.nacked ++ resendBuffer.nonAcked) foreach { s ⇒ context.system.deadLetters ! s.copy(seqOpt = None) }
    receiveBuffers.remove(Link(localAddress, remoteAddress))
    autoResendTimer.cancel()
    maxSilenceTimer.foreach(_.cancel())
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
    case IsIdle ⇒ // Do not reply, we will Terminate soon, or send a GotUid
    case s: Send ⇒
      handleSend(s)
    case ack: Ack ⇒
      // If we are not sure about the UID just ignore the ack. Ignoring is fine.
      if (uidConfirmed) {
        try resendBuffer = resendBuffer.acknowledge(ack)
        catch {
          case NonFatal(e) ⇒
            throw new HopelessAssociation(localAddress, remoteAddress, uid,
              new IllegalStateException(s"Error encountered while processing system message " +
                s"acknowledgement buffer: $resendBuffer ack: $ack", e))
        }

        resendNacked()
      }
    case AttemptSysMsgRedelivery ⇒
      if (uidConfirmed) resendAll()
    case Terminated(_) ⇒
      currentHandle = None
      context.parent ! StoppedReading(self)
      if (resendBuffer.nonAcked.nonEmpty || resendBuffer.nacked.nonEmpty)
        context.system.scheduler.scheduleOnce(settings.SysResendTimeout, self, AttemptSysMsgRedelivery)
      goToIdle()
    case g @ GotUid(receivedUid, _) ⇒
      bailoutAt = None
      context.parent ! g
      // New system that has the same address as the old - need to start from fresh state
      uidConfirmed = true
      if (uid.exists(_ != receivedUid)) reset()
      uid = Some(receivedUid)
      resendAll()

    case s: EndpointWriter.StopReading ⇒
      writer forward s
  }

  def gated(writerTerminated: Boolean, earlyUngateRequested: Boolean): Receive = {
    case Terminated(_) if !writerTerminated ⇒
      if (earlyUngateRequested)
        self ! Ungate
      else
        context.system.scheduler.scheduleOnce(settings.RetryGateClosedFor, self, Ungate)
      context.become(gated(writerTerminated = true, earlyUngateRequested))
    case IsIdle ⇒ sender() ! Idle
    case Ungate ⇒
      if (!writerTerminated) {
        // Ungate was sent from EndpointManager, but we must wait for Terminated first.
        context.become(gated(writerTerminated = false, earlyUngateRequested = true))
      } else if (resendBuffer.nonAcked.nonEmpty || resendBuffer.nacked.nonEmpty) {
        // If we talk to a system we have not talked to before (or has given up talking to in the past) stop
        // system delivery attempts after the specified time. This act will drop the pending system messages and gate the
        // remote address at the EndpointManager level stopping this actor. In case the remote system becomes reachable
        // again it will be immediately quarantined due to out-of-sync system message buffer and becomes quarantined.
        // In other words, this action is safe.
        if (bailoutAt.exists(_.isOverdue()))
          throw new HopelessAssociation(localAddress, remoteAddress, uid,
            new java.util.concurrent.TimeoutException("Delivery of system messages timed out and they were dropped."))
        writer = createWriter()
        // Resending will be triggered by the incoming GotUid message after the connection finished
        goToActive()
      } else goToIdle()
    case AttemptSysMsgRedelivery               ⇒ // Ignore
    case s @ Send(msg: SystemMessage, _, _, _) ⇒ tryBuffer(s.copy(seqOpt = Some(nextSeq())))
    case s: Send                               ⇒ context.system.deadLetters ! s
    case EndpointWriter.FlushAndStop           ⇒ context.stop(self)
    case EndpointWriter.StopReading(w, replyTo) ⇒
      replyTo ! EndpointWriter.StoppedReading(w)
      sender() ! EndpointWriter.StoppedReading(w)
  }

  def idle: Receive = {
    case IsIdle ⇒ sender() ! Idle
    case s: Send ⇒
      writer = createWriter()
      // Resending will be triggered by the incoming GotUid message after the connection finished
      handleSend(s)
      goToActive()
    case AttemptSysMsgRedelivery ⇒
      if (resendBuffer.nacked.nonEmpty || resendBuffer.nonAcked.nonEmpty) {
        writer = createWriter()
        // Resending will be triggered by the incoming GotUid message after the connection finished
        goToActive()
      }
    case TooLongIdle ⇒
      throw new HopelessAssociation(localAddress, remoteAddress, uid,
        new TimeoutException("Remote system has been silent for too long. " +
          s"(more than ${settings.QuarantineSilentSystemTimeout.toUnit(TimeUnit.HOURS)} hours)"))
    case EndpointWriter.FlushAndStop ⇒ context.stop(self)
    case EndpointWriter.StopReading(w, replyTo) ⇒
      replyTo ! EndpointWriter.StoppedReading(w)
  }

  private def goToIdle(): Unit = {
    if (bufferWasInUse && maxSilenceTimer.isEmpty)
      maxSilenceTimer = Some(context.system.scheduler.scheduleOnce(settings.QuarantineSilentSystemTimeout, self, TooLongIdle))
    context.become(idle)
  }

  private def goToActive(): Unit = {
    maxSilenceTimer.foreach(_.cancel())
    maxSilenceTimer = None
    context.become(receive)
  }

  def flushWait: Receive = {
    case IsIdle ⇒ // Do not reply, we will Terminate soon, which will do the inbound connection unstashing
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
      // GotUid will kick resendAll() causing the messages to be properly written.
      // Flow control by not sending more when we already have many outstanding.
      if (uidConfirmed && resendBuffer.nonAcked.size <= settings.SysResendLimit)
        writer ! sequencedSend
    } else writer ! send

  private def resendNacked(): Unit = resendBuffer.nacked foreach { writer ! _ }

  private def resendAll(): Unit = {
    resendNacked()
    resendBuffer.nonAcked.take(settings.SysResendLimit) foreach { writer ! _ }
  }

  private def tryBuffer(s: Send): Unit =
    try {
      resendBuffer = resendBuffer buffer s
      bufferWasInUse = true
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
  case class TakeOver(handle: AkkaProtocolHandle, replyTo: ActorRef) extends NoSerializationVerificationNeeded
  case class TookOver(writer: ActorRef, handle: AkkaProtocolHandle) extends NoSerializationVerificationNeeded
  case object BackoffTimer
  case object FlushAndStop
  private case object FlushAndStopTimeout
  case object AckIdleCheckTimer
  case class StopReading(writer: ActorRef, replyTo: ActorRef)
  case class StoppedReading(writer: ActorRef)

  case class Handle(handle: AkkaProtocolHandle) extends NoSerializationVerificationNeeded

  case class OutboundAck(ack: Ack)

  // These settings are not configurable because wrong configuration will break the auto-tuning
  private val SendBufferBatchSize = 5
  private val MinAdaptiveBackoffNanos = 300000L // 0.3 ms
  private val MaxAdaptiveBackoffNanos = 2000000L // 2 ms
  private val LogBufferSizeInterval = 5000000000L // 5 s, in nanoseconds
  private val MaxWriteCount = 50

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
  extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) {

  import EndpointWriter._
  import context.dispatcher

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val remoteMetrics = RemoteMetricsExtension(extendedSystem)
  val backoffDispatcher = context.system.dispatchers.lookup("akka.remote.backoff-remote-dispatcher")

  var reader: Option[ActorRef] = None
  var handle: Option[AkkaProtocolHandle] = handleOrActive
  val readerId = Iterator from 0

  def newAckDeadline: Deadline = Deadline.now + settings.SysMsgAckTimeout
  var ackDeadline: Deadline = newAckDeadline

  var lastAck: Option[Ack] = None

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case NonFatal(e) ⇒ publishAndThrow(e, Logging.ErrorLevel)
  }

  val provider = RARP(extendedSystem).provider
  val msgDispatch = new DefaultMessageDispatcher(extendedSystem, provider, log)

  val inbound = handle.isDefined
  var stopReason: DisassociateInfo = AssociationHandle.Unknown

  // Use an internal buffer instead of Stash for efficiency
  // stash/unstashAll is slow when many messages are stashed
  // IMPORTANT: sender is not stored, so sender() and forward must not be used in EndpointWriter
  val buffer = new java.util.LinkedList[AnyRef]
  val prioBuffer = new java.util.LinkedList[Send]
  var largeBufferLogTimestamp = System.nanoTime()

  private def publishAndThrow(reason: Throwable, logLevel: Logging.LogLevel): Nothing = {
    reason match {
      case _: EndpointDisassociatedException ⇒ publishDisassociated()
      case _                                 ⇒ publishError(reason, logLevel)
    }
    throw reason
  }

  val ackIdleTimer = {
    val interval = settings.SysMsgAckTimeout / 2
    context.system.scheduler.schedule(interval, interval, self, AckIdleCheckTimer)
  }

  override def preStart(): Unit = {
    handle match {
      case Some(h) ⇒
        reader = startReadEndpoint(h)
      case None ⇒
        transport.associate(remoteAddress, refuseUid).map(Handle(_)) pipeTo self
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("EndpointWriter must not be restarted")

  override def postStop(): Unit = {
    ackIdleTimer.cancel()
    while (!prioBuffer.isEmpty)
      extendedSystem.deadLetters ! prioBuffer.poll
    while (!buffer.isEmpty)
      extendedSystem.deadLetters ! buffer.poll
    handle foreach { _.disassociate(stopReason) }
    eventPublisher.notifyListeners(DisassociatedEvent(localAddress, remoteAddress, inbound))
  }

  def receive = if (handle.isEmpty) initializing else writing

  def initializing: Receive = {
    case s: Send ⇒
      enqueueInBuffer(s)
    case Status.Failure(e: InvalidAssociationException) ⇒
      publishAndThrow(new InvalidAssociation(localAddress, remoteAddress, e), Logging.WarningLevel)
    case Status.Failure(e) ⇒
      publishAndThrow(new EndpointAssociationException(s"Association failed with [$remoteAddress]", e), Logging.DebugLevel)
    case Handle(inboundHandle) ⇒
      // Assert handle == None?
      context.parent ! ReliableDeliverySupervisor.GotUid(inboundHandle.handshakeInfo.uid, remoteAddress)
      handle = Some(inboundHandle)
      reader = startReadEndpoint(inboundHandle)
      eventPublisher.notifyListeners(AssociatedEvent(localAddress, remoteAddress, inbound))
      becomeWritingOrSendBufferedMessages()
  }

  def enqueueInBuffer(msg: AnyRef): Unit = msg match {
    case s @ Send(_: PriorityMessage, _, _, _) ⇒ prioBuffer offer s
    case s @ Send(ActorSelectionMessage(_: PriorityMessage, _, _), _, _, _) ⇒ prioBuffer offer s
    case _ ⇒ buffer offer msg
  }

  val buffering: Receive = {
    case s: Send      ⇒ enqueueInBuffer(s)
    case BackoffTimer ⇒ sendBufferedMessages()
    case FlushAndStop ⇒
      // Flushing is postponed after the pending writes
      buffer offer FlushAndStop
      context.system.scheduler.scheduleOnce(settings.FlushWait, self, FlushAndStopTimeout)
    case FlushAndStopTimeout ⇒
      // enough
      flushAndStop()
  }

  def becomeWritingOrSendBufferedMessages(): Unit =
    if (buffer.isEmpty)
      context.become(writing)
    else {
      context.become(buffering)
      sendBufferedMessages()
    }

  var writeCount = 0
  var maxWriteCount = MaxWriteCount
  var adaptiveBackoffNanos = 1000000L // 1 ms
  var fullBackoff = false

  // FIXME remove these counters when tuning/testing is completed
  var fullBackoffCount = 1
  var smallBackoffCount = 0
  var noBackoffCount = 0

  def adjustAdaptiveBackup(): Unit = {
    maxWriteCount = math.max(writeCount, maxWriteCount)
    if (writeCount <= SendBufferBatchSize) {
      fullBackoff = true
      adaptiveBackoffNanos = math.min((adaptiveBackoffNanos * 1.2).toLong, MaxAdaptiveBackoffNanos)
    } else if (writeCount >= maxWriteCount * 0.6)
      adaptiveBackoffNanos = math.max((adaptiveBackoffNanos * 0.9).toLong, MinAdaptiveBackoffNanos)
    else if (writeCount <= maxWriteCount * 0.2)
      adaptiveBackoffNanos = math.min((adaptiveBackoffNanos * 1.1).toLong, MaxAdaptiveBackoffNanos)

    writeCount = 0
  }

  def sendBufferedMessages(): Unit = {

    def delegate(msg: Any): Boolean = msg match {
      case s: Send ⇒
        writeSend(s)
      case FlushAndStop ⇒
        flushAndStop()
        false
      case s @ StopReading(_, replyTo) ⇒
        reader.foreach(_.tell(s, replyTo))
        true
    }

    @tailrec def writeLoop(count: Int): Boolean =
      if (count > 0 && !buffer.isEmpty)
        if (delegate(buffer.peek)) {
          buffer.removeFirst()
          writeCount += 1
          writeLoop(count - 1)
        } else false
      else true

    @tailrec def writePrioLoop(): Boolean =
      if (prioBuffer.isEmpty) true
      else writeSend(prioBuffer.peek) && { prioBuffer.removeFirst(); writePrioLoop() }

    val size = buffer.size

    val ok = writePrioLoop() && writeLoop(SendBufferBatchSize)
    if (buffer.isEmpty && prioBuffer.isEmpty) {
      // FIXME remove this when testing/tuning is completed
      if (log.isDebugEnabled)
        log.debug(s"Drained buffer with maxWriteCount: $maxWriteCount, fullBackoffCount: $fullBackoffCount" +
          s", smallBackoffCount: $smallBackoffCount, noBackoffCount: $noBackoffCount " +
          s", adaptiveBackoff: ${adaptiveBackoffNanos / 1000}")
      fullBackoffCount = 1
      smallBackoffCount = 0
      noBackoffCount = 0

      writeCount = 0
      maxWriteCount = MaxWriteCount
      context.become(writing)
    } else if (ok) {
      noBackoffCount += 1
      self ! BackoffTimer
    } else {

      if (size > settings.LogBufferSizeExceeding) {
        val now = System.nanoTime()
        if (now - largeBufferLogTimestamp >= LogBufferSizeInterval) {
          log.warning("[{}] buffered messages in EndpointWriter for [{}]. " +
            "You should probably implement flow control to avoid flooding the remote connection.",
            size, remoteAddress)
          largeBufferLogTimestamp = now
        }
      }

      adjustAdaptiveBackup()
      scheduleBackoffTimer()
    }

  }

  def scheduleBackoffTimer(): Unit = {
    if (fullBackoff) {
      fullBackoffCount += 1
      fullBackoff = false
      context.system.scheduler.scheduleOnce(settings.BackoffPeriod, self, BackoffTimer)
    } else {
      smallBackoffCount += 1
      val s = self
      val backoffDeadlinelineNanoTime = System.nanoTime + adaptiveBackoffNanos
      Future {
        @tailrec def backoff(): Unit = {
          val backoffNanos = backoffDeadlinelineNanoTime - System.nanoTime
          if (backoffNanos > 0) {
            LockSupport.parkNanos(backoffNanos)
            // parkNanos allows for spurious wakeup, check again
            backoff()
          }
        }
        backoff()
        s.tell(BackoffTimer, ActorRef.noSender)
      }(backoffDispatcher)
    }
  }

  val writing: Receive = {
    case s: Send ⇒
      if (!writeSend(s)) {
        enqueueInBuffer(s)
        scheduleBackoffTimer()
        context.become(buffering)
      }

    // We are in Writing state, so buffer is empty, safe to stop here
    case FlushAndStop ⇒
      flushAndStop()

    case AckIdleCheckTimer if ackDeadline.isOverdue() ⇒
      trySendPureAck()
  }

  def writeSend(s: Send): Boolean = try {
    handle match {
      case Some(h) ⇒
        if (provider.remoteSettings.LogSend) {
          def msgLog = s"RemoteMessage: [${s.message}] to [${s.recipient}]<+[${s.recipient.path}] from [${s.senderOption.getOrElse(extendedSystem.deadLetters)}]"
          log.debug("sending message {}", msgLog)
        }

        val pdu = codec.constructMessage(
          s.recipient.localAddressToUse,
          s.recipient,
          serializeMessage(s.message),
          s.senderOption,
          seqOption = s.seqOpt,
          ackOption = lastAck)

        val pduSize = pdu.size
        remoteMetrics.logPayloadBytes(s.message, pduSize)

        if (pduSize > transport.maximumPayloadBytes) {
          val reason = new OversizedPayloadException(s"Discarding oversized payload sent to ${s.recipient}: max allowed size ${transport.maximumPayloadBytes} bytes, actual size of encoded ${s.message.getClass} was ${pdu.size} bytes.")
          log.error(reason, "Transient association error (association remains live)")
          true
        } else {
          val ok = h.write(pdu)
          if (ok) {
            ackDeadline = newAckDeadline
            lastAck = None
          }
          ok
        }

      case None ⇒
        throw new EndpointException("Internal error: Endpoint is in state Writing, but no association handle is present.")
    }
  } catch {
    case e: NotSerializableException ⇒
      log.error(e, "Transient association error (association remains live)")
      true
    case e: EndpointException ⇒
      publishAndThrow(e, Logging.ErrorLevel)
    case NonFatal(e) ⇒
      publishAndThrow(new EndpointException("Failed to write message to the transport", e), Logging.ErrorLevel)
  }

  def handoff: Receive = {
    case Terminated(_) ⇒
      reader = startReadEndpoint(handle.get)
      becomeWritingOrSendBufferedMessages()

    case s: Send ⇒
      enqueueInBuffer(s)

    case OutboundAck(_) ⇒
    // Ignore outgoing acks during take over, since we might have
    // replaced the handle with a connection to a new, restarted, system
    // and the ack might be targeted to the old incarnation.
    // See issue #19780
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(r) if r == reader.orNull ⇒
      publishAndThrow(new EndpointDisassociatedException("Disassociated"), Logging.DebugLevel)
    case s @ StopReading(_, replyTo) ⇒
      reader match {
        case Some(r) ⇒
          r.tell(s, replyTo)
        case None ⇒
          // initalizing, buffer and take care of it later when buffer is sent
          enqueueInBuffer(s)
      }
    case TakeOver(newHandle, replyTo) ⇒
      // Shutdown old reader
      handle foreach { _.disassociate() }
      handle = Some(newHandle)
      replyTo ! TookOver(self, newHandle)
      context.become(handoff)
    case FlushAndStop ⇒
      stopReason = AssociationHandle.Shutdown
      context.stop(self)
    case OutboundAck(ack) ⇒
      lastAck = Some(ack)
      if (ackDeadline.isOverdue())
        trySendPureAck()
    case AckIdleCheckTimer   ⇒ // Ignore
    case FlushAndStopTimeout ⇒ // ignore
    case BackoffTimer        ⇒ // ignore
    case other               ⇒ super.unhandled(other)
  }

  def flushAndStop(): Unit = {
    // Try to send a last Ack message
    trySendPureAck()
    stopReason = AssociationHandle.Shutdown
    context.stop(self)
  }

  private def trySendPureAck(): Unit =
    for (h ← handle; ack ← lastAck)
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

    case StopReading(writer, replyTo) ⇒
      saveState()
      context.become(notReading)
      replyTo ! StoppedReading(writer)

  }

  def notReading: Receive = {
    case Disassociated(info) ⇒ handleDisassociated(info)

    case StopReading(writer, replyTo) ⇒
      replyTo ! StoppedReading(writer)

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
