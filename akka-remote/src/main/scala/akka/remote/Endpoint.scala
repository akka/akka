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
import akka.actor.SupervisorStrategy.{ Restart, Stop }

trait InboundMessageDispatcher {
  def dispatch(recipient: InternalActorRef,
               recipientAddress: Address,
               serializedMessage: MessageProtocol,
               senderOption: Option[ActorRef]): Unit
}

class DefaultMessageDispatcher(private val system: ExtendedActorSystem,
                               private val provider: RemoteActorRefProvider,
                               private val log: LoggingAdapter) extends InboundMessageDispatcher {

  private val remoteDaemon = provider.remoteDaemon

  override def dispatch(recipient: InternalActorRef,
                        recipientAddress: Address,
                        serializedMessage: MessageProtocol,
                        senderOption: Option[ActorRef]): Unit = {

    import provider.remoteSettings._

    lazy val payload: AnyRef = MessageSerializer.deserialize(system, serializedMessage)
    lazy val payloadClass: Class[_] = if (payload eq null) null else payload.getClass
    val sender: ActorRef = senderOption.getOrElse(system.deadLetters)
    val originalReceiver = recipient.path

    lazy val msgLog = "RemoteMessage: " + payload + " to " + recipient + "<+{" + originalReceiver + "} from " + sender

    recipient match {

      case `remoteDaemon` ⇒
        if (LogReceive) log.debug("received daemon message {}", msgLog)
        payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case NonFatal(e) ⇒ log.error(e, "exception while processing remote command {} from {}", m, sender)
            }
          case x ⇒ log.debug("remoteDaemon received illegal message {} from {}", x, sender)
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

object EndpointWriter {

  case class TakeOver(handle: AssociationHandle)
  case object BackoffTimer

  sealed trait State
  case object Initializing extends State
  case object Buffering extends State
  case object Writing extends State
}

class EndpointException(msg: String, cause: Throwable) extends AkkaException(msg, cause)
case class InvalidAssociation(localAddress: Address, remoteAddress: Address, cause: Throwable)
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

  var reader: ActorRef = null
  var handle: AssociationHandle = handleOrActive.getOrElse(null)
  var inbound = false
  var readerId = 0

  override val supervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) ⇒
      publishAndThrow(e)
      Stop
  }

  val msgDispatch =
    new DefaultMessageDispatcher(extendedSystem, extendedSystem.provider.asInstanceOf[RemoteActorRefProvider], log)

  private def publishAndThrow(reason: Throwable): Nothing = {
    eventPublisher.notifyListeners(AssociationErrorEvent(reason, localAddress, remoteAddress, inbound))
    throw reason
  }

  private def publishAndThrow(message: String, cause: Throwable): Nothing =
    publishAndThrow(new EndpointException(message, cause))

  override def postRestart(reason: Throwable): Unit = {
    handle = null // Wipe out the possibly injected handle
    preStart()
  }

  override def preStart(): Unit = {
    if (handle eq null) {
      transport.associate(remoteAddress) pipeTo self
      inbound = false
      startWith(Initializing, ())
    } else {
      startReadEndpoint()
      inbound = true
      startWith(Writing, ())
    }
  }

  when(Initializing) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay
    case Event(Transport.Invalid(e), _) ⇒
      log.error(e, "Tried to associate with invalid remote address " + remoteAddress +
        ". Address is now quarantined, all messages to this address will be delivered to dead letters.")
      publishAndThrow(new InvalidAssociation(localAddress, remoteAddress, e))

    case Event(Transport.Fail(e), _) ⇒ publishAndThrow(s"Association failed with $remoteAddress", e)
    case Event(Transport.Ready(inboundHandle), _) ⇒
      handle = inboundHandle
      startReadEndpoint()
      goto(Writing)

  }

  when(Buffering) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      stash()
      stay

    case Event(BackoffTimer, _) ⇒ goto(Writing)
  }

  when(Writing) {
    case Event(Send(msg, senderOption, recipient), _) ⇒
      val pdu = codec.constructMessage(recipient.localAddressToUse, recipient, serializeMessage(msg), senderOption)
      val success = try handle.write(pdu) catch {
        case NonFatal(e) ⇒ publishAndThrow("Failed to write message to the transport", e)
      }
      if (success) stay else {
        stash()
        goto(Buffering)
      }
  }

  whenUnhandled {
    case Event(Terminated(r), _) if r == reader ⇒ publishAndThrow("Disassociated", null)
    case Event(TakeOver(newHandle), _) ⇒
      // Shutdown old reader
      if (handle ne null) handle.disassociate()
      if (reader ne null) {
        context.unwatch(reader)
        context.stop(reader)
      }
      handle = newHandle
      inbound = true
      startReadEndpoint()
      unstashAll()
      goto(Writing)
  }

  onTransition {
    case Initializing -> Writing ⇒
      unstashAll()
      eventPublisher.notifyListeners(AssociatedEvent(localAddress, remoteAddress, inbound))
    case Writing -> Buffering ⇒ setTimer("backoff-timer", BackoffTimer, settings.BackoffPeriod, false)
    case Buffering -> Writing ⇒
      unstashAll()
      cancelTimer("backoff-timer")
  }

  onTermination {
    case StopEvent(_, _, _) ⇒ if (handle ne null) {
      unstashAll()
      handle.disassociate()
      eventPublisher.notifyListeners(DisassociatedEvent(localAddress, remoteAddress, inbound))
    }
  }

  private def startReadEndpoint(): Unit = {
    reader = context.actorOf(Props(new EndpointReader(codec, handle.localAddress, msgDispatch)),
      "endpointReader-" + URLEncoder.encode(remoteAddress.toString, "utf-8"))
    readerId += 1
    handle.readHandlerPromise.success(reader)
    context.watch(reader)
  }

  private def serializeMessage(msg: Any): MessageProtocol = {
    Serialization.currentTransportAddress.withValue(handle.localAddress) {
      (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
    }
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
