/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.{ OnlyCauseStackTrace, AkkaException }
import akka.actor._
import akka.dispatch.SystemMessage
import akka.event.LoggingAdapter
import akka.pattern.pipe
import akka.remote.EndpointManager.Send
import akka.remote.RemoteProtocol.MessageProtocol
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.{ AkkaPduCodec, Transport, AssociationHandle }
import akka.serialization.Serialization
import akka.util.ByteString
import akka.remote.transport.Transport.InvalidAssociationException
import java.io.NotSerializableException
import scala.util.control.{ NoStackTrace, NonFatal }

/**
 * INTERNAL API
 */
private[remote] trait InboundMessageDispatcher {
  def dispatch(recipient: InternalActorRef,
               recipientAddress: Address,
               serializedMessage: MessageProtocol,
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
                        serializedMessage: MessageProtocol,
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
          payload match {
            case m @ (_: DaemonMsg | _: Terminated) ⇒
              remoteDaemon ! m
            case x ⇒ log.debug("remoteDaemon received illegal message {} from {}", x, sender)
          }
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
private[remote] object EndpointWriter {

  /**
   * This message signals that the current association maintained by the local EndpointWriter and EndpointReader is
   * to be overridden by a new inbound association. This is needed to avoid parallel inbound associations from the
   * same remote endpoint: when a parallel inbound association is detected, the old one is removed and the new one is
   * used instead.
   * @param handle Handle of the new inbound association.
   */
  case class TakeOver(handle: AssociationHandle)
  case object BackoffTimer
  case object FlushAndStop

  sealed trait State
  case object Initializing extends State
  case object Buffering extends State
  case object Writing extends State
  case object Handoff extends State
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
private[remote] class EndpointWriter(
  handleOrActive: Option[AssociationHandle],
  localAddress: Address,
  remoteAddress: Address,
  transport: Transport,
  settings: RemoteSettings,
  codec: AkkaPduCodec) extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) with Stash with FSM[EndpointWriter.State, Unit] {

  import EndpointWriter._
  import context.dispatcher

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]

  var reader: Option[ActorRef] = None
  var handle: Option[AssociationHandle] = handleOrActive // FIXME: refactor into state data
  val readerId = Iterator from 0

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

  override def preStart(): Unit =
    startWith(
      handle match {
        case Some(h) ⇒
          reader = startReadEndpoint(h)
          Writing
        case None ⇒
          transport.associate(remoteAddress) pipeTo self
          Initializing
      },
      ())

  when(Initializing) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay()
    case Event(Status.Failure(e: InvalidAssociationException), _) ⇒
      publishAndThrow(new InvalidAssociation(localAddress, remoteAddress, e))
    case Event(Status.Failure(e), _) ⇒
      publishAndThrow(new EndpointAssociationException(s"Association failed with [$remoteAddress]", e))
    case Event(inboundHandle: AssociationHandle, _) ⇒
      // Assert handle == None?
      handle = Some(inboundHandle)
      reader = startReadEndpoint(inboundHandle)
      goto(Writing)

  }

  when(Buffering) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay()

    case Event(BackoffTimer, _) ⇒ goto(Writing)

    case Event(FlushAndStop, _) ⇒
      stash() // Flushing is postponed after the pending writes
      stay()
  }

  when(Writing) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      try {
        handle match {
          case Some(h) ⇒
            val pdu = codec.constructMessage(recipient.localAddressToUse, recipient, serializeMessage(msg), senderOption)
            if (pdu.size > transport.maximumPayloadBytes) {
              publishAndStay(new OversizedPayloadException(s"Discarding oversized payload sent to ${recipient}: max allowed size ${transport.maximumPayloadBytes} bytes, actual size of encoded ${msg.getClass} was ${pdu.size} bytes."))
            } else if (h.write(pdu)) {
              stay()
            } else {
              stash()
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
    case Event(FlushAndStop, _) ⇒ stop()
  }

  when(Handoff) {
    case Event(Terminated(_), _) ⇒
      reader = startReadEndpoint(handle.get)
      unstashAll()
      goto(Writing)

    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay()

    // TakeOver messages are not handled here but inside the whenUnhandled block, because the procedure is exactly the
    // same. Any outstanding
  }

  whenUnhandled {
    case Event(Terminated(r), _) if r == reader.orNull ⇒ publishAndThrow(new EndpointDisassociatedException("Disassociated"))
    case Event(TakeOver(newHandle), _) ⇒
      // Shutdown old reader
      handle foreach { _.disassociate() }
      reader foreach context.stop
      handle = Some(newHandle)
      goto(Handoff)
    case Event(FlushAndStop, _) ⇒
      stop()
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
      // FIXME: Add a test case for this
      // It is important to call unstashAll() for the stash to work properly and maintain messages during restart.
      // As the FSM trait does not call super.postStop(), this call is needed
      unstashAll()
      handle foreach { _.disassociate() }
      eventPublisher.notifyListeners(DisassociatedEvent(localAddress, remoteAddress, inbound))
  }

  private def startReadEndpoint(handle: AssociationHandle): Some[ActorRef] = {
    val newReader =
      context.watch(context.actorOf(
        Props(new EndpointReader(localAddress, remoteAddress, transport, settings, codec, msgDispatch, inbound)),
        "endpointReader-" + AddressUrlEncoder(remoteAddress) + "-" + readerId.next()))
    handle.readHandlerPromise.success(ActorHandleEventListener(newReader))
    Some(newReader)
  }

  private def serializeMessage(msg: Any): MessageProtocol = handle match {
    case Some(h) ⇒
      Serialization.currentTransportAddress.withValue(h.localAddress) {
        (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
      }
    case None ⇒ throw new EndpointException("Internal error: No handle was present during serialization of" +
      "outbound message.")
  }

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
  val inbound: Boolean) extends EndpointActor(localAddress, remoteAddress, transport, settings, codec) {

  val provider = RARP(context.system).provider

  override def receive: Receive = {
    case Disassociated ⇒ context.stop(self)

    case InboundPayload(p) ⇒
      if (p.size > transport.maximumPayloadBytes) {
        publishError(new OversizedPayloadException(s"Discarding oversized payload received: max allowed size ${transport.maximumPayloadBytes} bytes, actual size ${p.size} bytes."))
      } else {
        val msg = decodePdu(p)
        msgDispatch.dispatch(msg.recipient, msg.recipientAddress, msg.serializedMessage, msg.senderOption)
      }
  }

  private def decodePdu(pdu: ByteString): Message = try {
    codec.decodeMessage(pdu, provider, localAddress)
  } catch {
    case NonFatal(e) ⇒ throw new EndpointException("Error while decoding incoming Akka PDU", e)
  }
}
