package akka.remote.transport

import akka.AkkaException
import akka.actor.AddressFromURIString
import akka.actor._
import akka.dispatch.SystemMessage
import akka.event.LoggingAdapter
import akka.pattern.pipe
import akka.remote.RemoteProtocol.MessageProtocol
import akka.remote._
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.EndpointWriter.Send
import akka.remote.transport.EndpointWriter.{BackoffOver, Retire}
import akka.serialization.Serialization
import akka.util.ByteString
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit._
import scala.concurrent.util.{Duration, FiniteDuration}
import scala.util.control.NonFatal

// TODO: doc defaults in reference.conf
class RemotingConfig(config: Config) {

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

  val BackoffPeriod: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.backoff-interval"), MILLISECONDS)

  val RequireCookie: Boolean = getBoolean("akka.remoting.require-cookie")

  val SecureCookie: String = getString("akka.remoting.secure-cookie")

}

// better name
trait MessageDispatcher {
  def dispatch(recipient: InternalActorRef,
               recipientAddress: Address,
               serializedMessage: MessageProtocol,
               senderOption: Option[ActorRef]): Unit
}

class DefaultMessageDispatcher( private val system: ExtendedActorSystem,
                                private val provider: RemoteActorRefProvider,
                                private val log: LoggingAdapter) extends MessageDispatcher {
  private val remoteDaemon = provider.remoteDaemon

  override def dispatch(recipient: InternalActorRef,
                        recipientAddress: Address,
                        serializedMessage: MessageProtocol,
                        senderOption: Option[ActorRef]): Unit = {
    val payload: AnyRef = MessageSerializer.deserialize(system, serializedMessage)
    val sender: ActorRef = senderOption.getOrElse(system.deadLetters)
    val originalReceiver = recipient.path

    lazy val msgLog = "RemoteMessage: " + payload + " to " + recipient + "<+{" + originalReceiver + "} from " + sender

    recipient match {
      case `remoteDaemon` ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received daemon message {}", msgLog)
        payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case NonFatal(e) ⇒ log.error(e, "exception while processing remote command {} from {}", m, sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, sender)
        }
      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received local message {}", msgLog)
        payload match {
          case msg: PossiblyHarmful if provider.remoteSettings.UntrustedMode ⇒
            log.warning("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type {}", msg.getClass)
          case msg: SystemMessage                       ⇒ l.sendSystemMessage(msg)
          case msg                                      ⇒ l.!(msg)(sender)
        }
      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received remote-destined message {}", msgLog)
        if (recipientAddress == provider.transport.address) {
            // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
            r.!(payload)(sender)
        } else {
          log.error("dropping message {} for non-local recipient {} arriving at {} inbound address is {}",
            payload, r, recipientAddress, provider.transport.address)
        }

      case r ⇒ log.error("dropping message {} for unknown recipient {} arriving at {} inbound address is {}",
        payload, r, recipientAddress, provider.transport.address)
    }
  }

}

object EndpointWriter {

  case object Retire

  case class Send(msg: Any, recipient: ActorRef, senderOption: Option[ActorRef])

  case object BackoffOver
}

class EndpointException(msg: String, cause: Throwable) extends AkkaException(msg, cause)
class InvalidAssociation(remoteAddress: Address) extends EndpointException(s"Invalid address: $remoteAddress", null)

//TODO: add configurable dispatcher
class EndpointWriter(
                      val active: Boolean,
                      handleOption: Option[AssociationHandle],
                      val remoteAddress: Address,
                      val transport: Transport,
                      val config: RemotingConfig,
                      val codec: AkkaPduCodec) extends Actor with Stash {

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]
  var reader: ActorRef = null
  var handle: AssociationHandle = null
  var buffering = true
  val msgDispatch =
    new DefaultMessageDispatcher(extendedSystem, extendedSystem.provider.asInstanceOf[RemoteActorRefProvider], null)

  import context.dispatcher

  if (active) {
    transport.associate(remoteAddress) pipeTo self
  } else {
    handleOption match {
      case Some(h) => handle = h
      case None => throw new EndpointException("Passive connections need an already associated handle injected", null)
    }
    startReadEndpoint()
  }

  def receive: Receive = {
    case Send(msg, recipient, senderOption) => if (!buffering) {
      sendMessage(msg, recipient, senderOption)
    } else {
      stash()
    }

    case Transport.Invalid => throw new InvalidAssociation(remoteAddress)

    case Transport.Fail(e) => throw new EndpointException(s"Association failed with $remoteAddress", e)

    case Transport.Ready(inboundHandle) =>
      handle = inboundHandle
      onBufferingOver()

    case BackoffOver => onBufferingOver()

    case Retire => context.stop(self)

  }

  def startReadEndpoint(): Unit = {
    onBufferingOver()

    reader = context.actorOf(Props(
        new EndpointReader(handle, self, config, codec, msgDispatch)
      ), "reader")
  }

  def onBufferingOver(): Unit = {
    buffering = false; unstashAll()
  }

  def backoff(): Unit = {
    buffering = true
    context.system.scheduler.scheduleOnce(config.BackoffPeriod, self, BackoffOver)
  }

  def serializeMessage(msg: Any): MessageProtocol = {
    Serialization.currentTransportAddress.withValue(handle.localAddress) {
      (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
    }
  }

  def sendMessage(msg: Any, recipient: ActorRef, senderOption: Option[ActorRef]): Unit = {
    val pdu = codec.constructMessagePdu(handle.localAddress, recipient, serializeMessage(msg), senderOption)
    try {
      if (!handle.write(pdu)) {
        stash()
        backoff()
      }
    } catch {
      case NonFatal(e) => throw new EndpointException("Failed to write message to the transport", e)
    }

  }

}

//TODO: logging
class EndpointReader(
                      val handle: AssociationHandle,
                      val writer: ActorRef,
                      val config: RemotingConfig,
                      val codec: AkkaPduCodec,
                      val msgDispatch: MessageDispatcher) extends Actor{

  val provider = context.system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]

  handle.readHandlerPromise.success(self)

  override def receive: Receive = {
    case Disassociated => writer ! Retire

    case InboundPayload(p) => decodePdu(p) match {

      case Message(recipient, recipientAddress, serializedMessage, senderOption) =>
        msgDispatch.dispatch(recipient, recipientAddress, serializedMessage, senderOption)

      case _ =>
    }
  }

  def decodePdu(pdu: ByteString): AkkaPdu = try {
    codec.decodePdu(pdu, provider)
  } catch {
    case NonFatal(e) ⇒ throw new EndpointException("Error while decoding incoming Akka PDU", e)
  }
}
