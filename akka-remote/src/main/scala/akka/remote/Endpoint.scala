package akka.remote

import akka.AkkaException
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
import java.net.URLEncoder
import scala.util.control.NonFatal

/**
 * Internal API
 */
private[remote] trait InboundMessageDispatcher {
  def dispatch(recipient: InternalActorRef,
               recipientAddress: Address,
               serializedMessage: MessageProtocol,
               senderOption: Option[ActorRef]): Unit
}

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
        if (UntrustedMode) log.debug("dropping daemon message in untrusted mode") else {
          if (LogReceive) log.debug("received daemon message {}", msgLog)
          payload match {
            case m @ (_: DaemonMsg | _: Terminated) ⇒
              try remoteDaemon ! m catch {
                case NonFatal(e) ⇒ log.error(e, "exception while processing remote command {} from {}", m, sender)
              }
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
 * Internal API
 */
private[remote] object EndpointWriter {

  /**
   * This message signals that the current association maintained by the local EndpointWriter and EndpointReader is
   * to be overridden by a new inbound association. This is needed to avoid parallel inbound associations from the
   * same remote endpoint.
   * @param handle
   */
  case class TakeOver(handle: AssociationHandle)
  case object BackoffTimer

  sealed trait State
  case object Initializing extends State
  case object Buffering extends State
  case object Writing extends State
}

private[remote] class EndpointException(msg: String, cause: Throwable) extends AkkaException(msg, cause)
private[remote] case class InvalidAssociation(localAddress: Address, remoteAddress: Address, cause: Throwable)
  extends EndpointException("Invalid address: " + remoteAddress, cause)

private[remote] class EndpointWriter(
  handleOrActive: Option[AssociationHandle],
  val localAddress: Address,
  val remoteAddress: Address,
  val transport: Transport,
  val settings: RemotingSettings,
  val codec: AkkaPduCodec) extends Actor with Stash with FSM[EndpointWriter.State, Unit] {

  import EndpointWriter._
  import context.dispatcher

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val eventPublisher = new EventPublisher(context.system, log, settings.LogLifecycleEvents)

  var reader: Option[ActorRef] = None
  var handle: Option[AssociationHandle] = handleOrActive // FIXME: refactor into state data
  val readerId = Iterator from 0

  override val supervisorStrategy = OneForOneStrategy() { case NonFatal(e) ⇒ publishAndThrow(e) }

  val msgDispatch =
    new DefaultMessageDispatcher(extendedSystem, extendedSystem.provider.asInstanceOf[RemoteActorRefProvider], log)

  def inbound = handle.isDefined

  private def publishAndThrow(reason: Throwable): Nothing =
    try
      // FIXME: Casting seems very evil here...
      eventPublisher.notifyListeners(AssociationErrorEvent(reason, localAddress, remoteAddress, inbound)).asInstanceOf[Nothing]
    finally throw reason

  override def postRestart(reason: Throwable): Unit = {
    handle = None // Wipe out the possibly injected handle
    preStart()
  }

  override def preStart(): Unit = {
    if (!inbound) {
      transport.associate(remoteAddress) pipeTo self
      startWith(Initializing, ())
    } else {
      startReadEndpoint()
      startWith(Writing, ())
    }
  }

  when(Initializing) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay()
    case Event(Transport.Invalid(e), _) ⇒
      log.error(e, "Tried to associate with invalid remote address [{}]. " +
        "Address is now quarantined, all messages to this address will be delivered to dead letters.", remoteAddress)
      publishAndThrow(new InvalidAssociation(localAddress, remoteAddress, e))

    case Event(Transport.Fail(e), _) ⇒
      publishAndThrow(new EndpointException(s"Association failed with [$remoteAddress]", e))
    case Event(Transport.Ready(inboundHandle), _) ⇒
      handle = Some(inboundHandle)
      startReadEndpoint()
      goto(Writing)

  }

  when(Buffering) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay()

    case Event(BackoffTimer, _) ⇒ goto(Writing)
  }

  when(Writing) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      val pdu = codec.constructMessage(recipient.localAddressToUse, recipient, serializeMessage(msg), senderOption)
      val success = try handle match {
        case Some(h) ⇒ h.write(pdu)
        case None ⇒ throw new EndpointException("Internal error: Endpoint is in state Writing, but no association" +
          "handle is present.", null)
      } catch {
        case NonFatal(e) ⇒ publishAndThrow(new EndpointException("Failed to write message to the transport", e))
      }
      if (success) stay() else {
        stash()
        goto(Buffering)
      }
  }

  whenUnhandled {
    case Event(Terminated(r), _) if r == reader ⇒ publishAndThrow(new EndpointException("Disassociated", null))
    case Event(TakeOver(newHandle), _) ⇒
      // Shutdown old reader
      handle foreach { _.disassociate() }
      reader match {
        case Some(r) ⇒
          context.unwatch(r)
          context.stop(r)
        case None ⇒
      }
      handle = Some(newHandle)
      startReadEndpoint()
      unstashAll()
      goto(Writing)
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
    case StopEvent(_, _, _) ⇒ if (handle ne null) {
      // FIXME: Add a test case for this
      // It is important to call unstashAll() for the stash to work properly and maintain messages during restart.
      // As the FSM trait does not call super.postStop(), this call is needed
      unstashAll()
      handle foreach { _.disassociate() }
      eventPublisher.notifyListeners(DisassociatedEvent(localAddress, remoteAddress, inbound))
    }
  }

  private def startReadEndpoint(): Unit = handle match {
    case Some(h) ⇒
      reader = Some(context.watch(context.actorOf(Props(new EndpointReader(codec, h.localAddress, msgDispatch)),
        "endpointReader-" + URLEncoder.encode(remoteAddress.toString, "utf-8") + "-" + readerId.next())))
      h.readHandlerPromise.success(reader.get)
    case None ⇒ throw new EndpointException("Internal error: No handle was present during creation of the endpoint" +
      "reader.", null)
  }

  private def serializeMessage(msg: Any): MessageProtocol = handle match {
    case Some(h) ⇒
      Serialization.currentTransportAddress.withValue(h.localAddress) {
        (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
      }
    case None ⇒ throw new EndpointException("Internal error: No handle was present during serialization of" +
      "outbound message.", null)
  }

}

private[remote] class EndpointReader(
  val codec: AkkaPduCodec,
  val localAddress: Address,
  val msgDispatch: InboundMessageDispatcher) extends Actor {

  val provider = context.system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]

  override def receive: Receive = {
    case Disassociated ⇒ context.stop(self)

    case InboundPayload(p) ⇒
      val msg = decodePdu(p)
      msgDispatch.dispatch(msg.recipient, msg.recipientAddress, msg.serializedMessage, msg.senderOption)
  }

  private def decodePdu(pdu: ByteString): Message = try {
    codec.decodeMessage(pdu, provider, localAddress)
  } catch {
    case NonFatal(e) ⇒ throw new EndpointException("Error while decoding incoming Akka PDU", e)
  }
}
