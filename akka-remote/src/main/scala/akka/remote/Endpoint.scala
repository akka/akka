/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.dispatch.sysmsg.SystemMessage
import akka.event.LoggingAdapter
import akka.pattern.pipe
import akka.remote.EndpointManager.Send
import akka.remote.WireFormats.SerializedMessage
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.Transport.InvalidAssociationException
import akka.remote.transport.{ AkkaPduProtobufCodec, AkkaPduCodec, Transport, AkkaProtocolHandle }
import akka.serialization.Serialization
import akka.util.ByteString
import akka.{ OnlyCauseStackTrace, AkkaException }
import java.io.NotSerializableException
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.{ Duration, Deadline }
import scala.util.control.NonFatal
import scala.annotation.tailrec
import akka.remote.EndpointWriter.FlushAndStop
import akka.actor.SupervisorStrategy._
import akka.remote.EndpointManager.Link

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

    def msgLog = s"RemoteMessage: [$payload] to [$recipient]<+[$originalReceiver] from [$sender]"

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
          case msg: PossiblyHarmful if UntrustedMode ⇒
            log.debug("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type {}", msg.getClass)
          case msg: SystemMessage ⇒ l.sendSystemMessage(msg)
          case msg                ⇒ l.!(msg)(sender)
        }

      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal && !UntrustedMode ⇒
        if (LogReceive) log.debug("received remote-destined message {}", msgLog)
        if (provider.transport.addresses(recipientAddress))
          // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
          r.!(payload)(sender)
        else
          log.error("dropping message {} for non-local recipient {} arriving at {} inbound addresses are {}",
            payloadClass, r, recipientAddress, provider.transport.addresses)

      case r ⇒ log.error("dropping message {} for unknown recipient {} arriving at {} inbound addresses are {}",
        payloadClass, r, recipientAddress, provider.transport.addresses)

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
@SerialVersionUID(1L)
private[remote] case class InvalidAssociation(localAddress: Address, remoteAddress: Address, cause: Throwable)
  extends EndpointException("Invalid address: " + remoteAddress, cause)

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[remote] case class HopelessAssociation(localAddress: Address, remoteAddress: Address, uid: Option[Int], cause: Throwable)
  extends EndpointException("Catastrophic association error.")

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
private[remote] class QuarantinedUidException(uid: Int, remoteAddress: Address)
  extends EndpointException(s"Refused association to [$remoteAddress] because its UID [$uid] is quarantined.")

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
  case class GotUid(uid: Int)

  def apply(
    handleOrActive: Option[AkkaProtocolHandle],
    localAddress: Address,
    remoteAddress: Address,
    transport: Transport,
    settings: RemoteSettings,
    codec: AkkaPduCodec,
    refuseUid: Option[Int],
    receiveBuffers: ConcurrentHashMap[Link, AckedReceiveBuffer[Message]]): Props =
    Props(classOf[ReliableDeliverySupervisor], handleOrActive, localAddress, remoteAddress, transport, settings,
      codec, refuseUid, receiveBuffers)
}

/**
 * INTERNAL API
 */
private[remote] class ReliableDeliverySupervisor(
  handleOrActive: Option[AkkaProtocolHandle],
  val localAddress: Address,
  val remoteAddress: Address,
  val transport: Transport,
  val settings: RemoteSettings,
  val codec: AkkaPduCodec,
  val refuseUid: Option[Int],
  val receiveBuffers: ConcurrentHashMap[Link, AckedReceiveBuffer[Message]]) extends Actor {
  import ReliableDeliverySupervisor._

  def retryGateEnabled = settings.RetryGateClosedFor > Duration.Zero

  override val supervisorStrategy = OneForOneStrategy(settings.MaximumRetriesInWindow, settings.RetryWindow, loggingEnabled = false) {
    case e @ (_: InvalidAssociation | _: HopelessAssociation | _: QuarantinedUidException) ⇒ Escalate
    case NonFatal(e) ⇒
      if (retryGateEnabled) {
        import context.dispatcher
        context.become(gated)
        context.system.scheduler.scheduleOnce(settings.RetryGateClosedFor, self, Ungate)
        context.unwatch(writer)
        currentHandle = None
        Stop
      } else {
        Restart
      }
  }

  var currentHandle: Option[AkkaProtocolHandle] = handleOrActive
  var resendBuffer = new AckedSendBuffer[Send](settings.SysMsgBufferSize)
  var resendDeadline = Deadline.now + settings.SysResendTimeout
  var lastCumulativeAck = SeqNo(-1)

  val nextSeq = {
    var seqCounter: Long = 0L
    () ⇒ {
      val tmp = seqCounter
      seqCounter += 1
      SeqNo(tmp)
    }
  }

  var writer: ActorRef = createWriter()
  var uid: Option[Int] = handleOrActive map { _.handshakeInfo.uid }

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
      resendBuffer = resendBuffer.acknowledge(ack)
      if (lastCumulativeAck < ack.cumulativeAck) {
        resendDeadline = Deadline.now + settings.SysResendTimeout
        lastCumulativeAck = ack.cumulativeAck
      } else if (resendDeadline.isOverdue()) {
        resendAll()
        resendDeadline = Deadline.now + settings.SysResendTimeout
      }
      resendNacked()
    case Terminated(_) ⇒
      currentHandle = None
      context.become(idle)
    case GotUid(u) ⇒ uid = Some(u)
  }

  def gated: Receive = {
    case Ungate ⇒
      if (resendBuffer.nonAcked.nonEmpty || resendBuffer.nacked.nonEmpty) {
        writer = createWriter()
        resendAll()
        context.become(receive)
      } else context.become(idle)
    case s @ Send(msg: SystemMessage, _, _, _) ⇒ tryBuffer(s.copy(seqOpt = Some(nextSeq())))
    case s: Send                               ⇒ context.system.deadLetters ! s
    case FlushAndStop                          ⇒ context.stop(self)
    case _                                     ⇒ // Ignore
  }

  def idle: Receive = {
    case s: Send ⇒
      writer = createWriter()
      resendAll()
      handleSend(s)
      context.become(receive)
    case FlushAndStop ⇒ context.stop(self)
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
      writer ! sequencedSend
    } else writer ! send

  private def resendNacked(): Unit = resendBuffer.nacked foreach { writer ! _ }

  private def resendAll(): Unit = {
    resendNacked()
    resendBuffer.nonAcked foreach { writer ! _ }
  }

  private def tryBuffer(s: Send): Unit =
    try {
      resendBuffer = resendBuffer buffer s
    } catch {
      case NonFatal(e) ⇒ throw new HopelessAssociation(localAddress, remoteAddress, uid, e)
    }

  private def createWriter(): ActorRef = {
    context.watch(context.actorOf(EndpointWriter(
      handleOrActive = currentHandle,
      localAddress = localAddress,
      remoteAddress = remoteAddress,
      transport = transport,
      settings = settings,
      AkkaPduProtobufCodec,
      refuseUid,
      receiveBuffers = receiveBuffers,
      reliableDeliverySupervisor = Some(self))
      .withDispatcher("akka.remote.writer-dispatcher"),
      "endpointWriter"))
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

  val eventPublisher = new EventPublisher(context.system, log, settings.LogRemoteLifecycleEvents)

  def publishError(reason: Throwable): Unit = {
    try
      eventPublisher.notifyListeners(AssociationErrorEvent(reason, localAddress, remoteAddress, inbound))
    catch { case NonFatal(e) ⇒ log.error(e, "Unable to publish error event to EventStream.") }
  }
}

/**
 * INTERNAL API
 */
private[remote] object EndpointWriter {

  def apply(
    handleOrActive: Option[AkkaProtocolHandle],
    localAddress: Address,
    remoteAddress: Address,
    transport: Transport,
    settings: RemoteSettings,
    codec: AkkaPduCodec,
    refuseUid: Option[Int],
    receiveBuffers: ConcurrentHashMap[Link, AckedReceiveBuffer[Message]],
    reliableDeliverySupervisor: Option[ActorRef]): Props =
    Props(classOf[EndpointWriter], handleOrActive, localAddress, remoteAddress, transport, settings, codec,
      refuseUid, receiveBuffers, reliableDeliverySupervisor)

  /**
   * This message signals that the current association maintained by the local EndpointWriter and EndpointReader is
   * to be overridden by a new inbound association. This is needed to avoid parallel inbound associations from the
   * same remote endpoint: when a parallel inbound association is detected, the old one is removed and the new one is
   * used instead.
   * @param handle Handle of the new inbound association.
   */
  case class TakeOver(handle: AkkaProtocolHandle)
  case object BackoffTimer
  case object FlushAndStop
  case object AckIdleCheckTimer

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
  transport: Transport,
  settings: RemoteSettings,
  codec: AkkaPduCodec,
  val refuseUid: Option[Int],
  val receiveBuffers: ConcurrentHashMap[Link, AckedReceiveBuffer[Message]],
  val reliableDeliverySupervisor: Option[ActorRef])
  extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) with UnboundedStash
  with FSM[EndpointWriter.State, Unit] {

  import EndpointWriter._
  import context.dispatcher

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val remoteMetrics = RemoteMetricsExtension(context.system)

  var reader: Option[ActorRef] = None
  var handle: Option[AkkaProtocolHandle] = handleOrActive // FIXME: refactor into state data
  val readerId = Iterator from 0

  def newAckDeadline: Deadline = Deadline.now + settings.SysMsgAckTimeout
  var ackDeadline: Deadline = newAckDeadline

  var lastAck: Option[Ack] = None

  override val supervisorStrategy = OneForOneStrategy() { case NonFatal(e) ⇒ publishAndThrow(e) }

  val msgDispatch = new DefaultMessageDispatcher(extendedSystem, RARP(extendedSystem).provider, log)

  var inbound = handle.isDefined

  private def publishAndThrow(reason: Throwable): Nothing = {
    publishError(reason)
    throw reason
  }

  private def publishAndStay(reason: Throwable): State = {
    publishError(reason)
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
          transport.associate(remoteAddress) pipeTo self
          Initializing
      },
      stateData = ())
  }

  when(Initializing) {
    case Event(Send(msg, senderOption, recipient, _), _) ⇒
      stash()
      stay()
    case Event(Status.Failure(e: InvalidAssociationException), _) ⇒
      publishAndThrow(new InvalidAssociation(localAddress, remoteAddress, e))
    case Event(Status.Failure(e), _) ⇒
      refuseUid match {
        case Some(uid) ⇒
          // don't try again when endpoint is quarantined
          publishAndThrow(new QuarantinedUidException(uid, remoteAddress))
        case None ⇒
          publishAndThrow(new EndpointAssociationException(s"Association failed with [$remoteAddress]", e))
      }
    case Event(inboundHandle: AkkaProtocolHandle, _) ⇒
      refuseUid match {
        case Some(uid) if inboundHandle.handshakeInfo.uid == uid ⇒
          publishAndThrow(new QuarantinedUidException(inboundHandle.handshakeInfo.uid, inboundHandle.remoteAddress))
        case _ ⇒ // Everything is fine
      }
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
            ackDeadline = newAckDeadline

            val pdu = codec.constructMessage(
              recipient.localAddressToUse,
              recipient,
              serializeMessage(msg),
              senderOption,
              seqOption = seqOption,
              ackOption = lastAck)

            val pduSize = pdu.size
            remoteMetrics.logPayloadBytes(msg, pduSize)

            if (pduSize > transport.maximumPayloadBytes) {
              publishAndStay(new OversizedPayloadException(s"Discarding oversized payload sent to ${recipient}: max allowed size ${transport.maximumPayloadBytes} bytes, actual size of encoded ${msg.getClass} was ${pdu.size} bytes."))
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
        case e: NotSerializableException ⇒ publishAndStay(e)
        case e: EndpointException        ⇒ publishAndThrow(e)
        case NonFatal(e)                 ⇒ publishAndThrow(new EndpointException("Failed to write message to the transport", e))
      }

    // We are in Writing state, so stash is empty, safe to stop here
    case Event(FlushAndStop, _) ⇒
      // Try to send a last Ack message
      trySendPureAck()
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

    case Event(Send(msg, senderOption, recipient, _), _) ⇒
      stash()
      stay()

  }

  whenUnhandled {
    case Event(Terminated(r), _) if r == reader.orNull ⇒
      publishAndThrow(new EndpointDisassociatedException("Disassociated"))
    case Event(TakeOver(newHandle), _) ⇒
      // Shutdown old reader
      handle foreach { _.disassociate() }
      reader foreach context.stop
      handle = Some(newHandle)
      goto(Handoff)
    case Event(FlushAndStop, _) ⇒
      stop()
    case Event(OutboundAck(ack), _) ⇒
      lastAck = Some(ack)
      trySendPureAck()
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
      handle foreach { _.disassociate() }
      eventPublisher.notifyListeners(DisassociatedEvent(localAddress, remoteAddress, inbound))
  }

  private def trySendPureAck(): Unit = for (h ← handle; ack ← lastAck)
    if (h.write(codec.constructPureAck(ack))) ackDeadline = newAckDeadline

  private def startReadEndpoint(handle: AkkaProtocolHandle): Some[ActorRef] = {
    val newReader =
      context.watch(context.actorOf(
        EndpointReader(localAddress, remoteAddress, transport, settings, codec,
          msgDispatch, inbound, reliableDeliverySupervisor, receiveBuffers),
        "endpointReader-" + AddressUrlEncoder(remoteAddress) + "-" + readerId.next()))
    handle.readHandlerPromise.success(ActorHandleEventListener(newReader))
    Some(newReader)
  }

  private def serializeMessage(msg: Any): SerializedMessage = handle match {
    case Some(h) ⇒
      Serialization.currentTransportInformation.withValue(Serialization.Information(h.localAddress, context.system)) {
        (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
      }
    case None ⇒ throw new EndpointException("Internal error: No handle was present during serialization of" +
      "outbound message.")
  }

}

/**
 * INTERNAL API
 */
private[remote] object EndpointReader {

  def apply(
    localAddress: Address,
    remoteAddress: Address,
    transport: Transport,
    settings: RemoteSettings,
    codec: AkkaPduCodec,
    msgDispatch: InboundMessageDispatcher,
    inbound: Boolean,
    reliableDeliverySupervisor: Option[ActorRef],
    receiveBuffers: ConcurrentHashMap[Link, AckedReceiveBuffer[Message]]): Props =
    Props(classOf[EndpointReader], localAddress, remoteAddress, transport, settings, codec, msgDispatch, inbound,
      reliableDeliverySupervisor, receiveBuffers)

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
  val reliableDeliverySupervisor: Option[ActorRef],
  val receiveBuffers: ConcurrentHashMap[Link, AckedReceiveBuffer[Message]]) extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) {

  import EndpointWriter.OutboundAck

  val provider = RARP(context.system).provider
  var ackedReceiveBuffer = new AckedReceiveBuffer[Message]

  override def preStart(): Unit = {
    receiveBuffers.get(Link(localAddress, remoteAddress)) match {
      case null ⇒
      case buf ⇒
        ackedReceiveBuffer = buf
        deliverAndAck()
    }
  }

  override def postStop(): Unit = {

    @tailrec
    def updateSavedState(key: Link, expectedState: AckedReceiveBuffer[Message]): Unit = {
      if (expectedState eq null) {
        if (receiveBuffers.putIfAbsent(key, ackedReceiveBuffer) ne null) updateSavedState(key, receiveBuffers.get(key))
      } else if (!receiveBuffers.replace(key, expectedState, expectedState.mergeFrom(ackedReceiveBuffer)))
        updateSavedState(key, receiveBuffers.get(key))
    }

    val key = Link(localAddress, remoteAddress)
    updateSavedState(key, receiveBuffers.get(key))
  }

  override def receive: Receive = {
    case Disassociated ⇒ context.stop(self)

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
      publishError(new OversizedPayloadException(s"Discarding oversized payload received: " +
        s"max allowed size [${transport.maximumPayloadBytes}] bytes, actual size [${oversized.size}] bytes."))

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
