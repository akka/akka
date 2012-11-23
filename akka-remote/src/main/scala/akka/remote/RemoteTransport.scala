/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.reflect.BeanProperty
import akka.dispatch.SystemMessage
import akka.event.{ LoggingAdapter, Logging }
import akka.AkkaException
import akka.serialization.Serialization
import akka.remote.RemoteProtocol._
import akka.actor._
import scala.collection.immutable
import scala.concurrent.Future

/**
 * Remote life-cycle events.
 */
sealed trait RemoteLifeCycleEvent extends Serializable {
  def logLevel: Logging.LogLevel
}

/**
 * Life-cycle events for RemoteClient.
 */
trait RemoteClientLifeCycleEvent extends RemoteLifeCycleEvent {
  def remoteAddress: Address
  final def getRemoteAddress: Address = remoteAddress
}

/**
 * A RemoteClientError is a general error that is thrown within or from a RemoteClient
 */
case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @transient remote: RemoteTransport,
  remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = "RemoteClientError@" + remoteAddress + ": Error[" + Logging.stackTraceFor(cause) + "]"
}

/**
 * RemoteClientDisconnected is published when a RemoteClient's connection is disconnected
 */
case class RemoteClientDisconnected(
  @transient @BeanProperty remote: RemoteTransport,
  remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String = "RemoteClientDisconnected@" + remoteAddress
}

/**
 * RemoteClientConnected is published when a RemoteClient's connection is established
 */
case class RemoteClientConnected(
  @transient @BeanProperty remote: RemoteTransport,
  remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String = "RemoteClientConnected@" + remoteAddress
}

/**
 * RemoteClientStarted is published when a RemoteClient has started up
 */
case class RemoteClientStarted(
  @transient @BeanProperty remote: RemoteTransport,
  remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "RemoteClientStarted@" + remoteAddress
}

/**
 * RemoteClientShutdown is published when a RemoteClient has shut down
 */
case class RemoteClientShutdown(
  @transient @BeanProperty remote: RemoteTransport,
  remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "RemoteClientShutdown@" + remoteAddress
}

/**
 *  Life-cycle events for RemoteServer.
 */
trait RemoteServerLifeCycleEvent extends RemoteLifeCycleEvent

/**
 * RemoteServerStarted is published when a local RemoteServer has started up
 */
case class RemoteServerStarted(
  @transient @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "RemoteServerStarted@" + remote
}

/**
 * RemoteServerShutdown is published when a local RemoteServer has shut down
 */
case class RemoteServerShutdown(
  @transient @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "RemoteServerShutdown@" + remote
}

/**
 * A RemoteServerError is a general error that is thrown within or from a RemoteServer
 */
case class RemoteServerError(
  @BeanProperty val cause: Throwable,
  @transient @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {

  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = "RemoteServerError@" + remote + "] Error[" + cause + "]"
}

/**
 * RemoteServerClientConnected is published when an inbound connection has been established
 */
case class RemoteServerClientConnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String =
    "RemoteServerClientConnected@" + remote + ": Client[" + clientAddress.getOrElse("no address") + "]"
}

/**
 * RemoteServerClientConnected is published when an inbound connection has been disconnected
 */
case class RemoteServerClientDisconnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String =
    "RemoteServerClientDisconnected@" + remote + ": Client[" + clientAddress.getOrElse("no address") + "]"
}

/**
 * RemoteServerClientClosed is published when an inbound RemoteClient is closed
 */
case class RemoteServerClientClosed(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String =
    "RemoteServerClientClosed@" + remote + ": Client[" + clientAddress.getOrElse("no address") + "]"
}

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka] (
  message: String,
  @transient @BeanProperty val client: RemoteTransport,
  val remoteAddress: Address, cause: Throwable = null) extends AkkaException(message, cause)

/**
 * RemoteTransportException represents a general failure within a RemoteTransport,
 * such as inability to start, wrong configuration etc.
 */
class RemoteTransportException(message: String, cause: Throwable) extends AkkaException(message, cause)

/**
 * The remote transport is responsible for sending and receiving messages.
 * Each transport has an address, which it should provide in
 * Serialization.currentTransportAddress (thread-local) while serializing
 * actor references (which might also be part of messages). This address must
 * be available (i.e. fully initialized) by the time the first message is
 * received or when the start() method returns, whatever happens first.
 */
abstract class RemoteTransport(val system: ExtendedActorSystem, val provider: RemoteActorRefProvider) {
  /**
   * Shuts down the remoting
   */
  def shutdown(): Unit

  /**
   * Address to be used in RootActorPath of refs generated for this transport.
   */
  def addresses: immutable.Set[Address]

  /**
   * The default transport address of the actorsystem
   * @return The listen address of the default transport
   */
  def defaultAddress: Address

  /**
   * Resolves the correct local address to be used for contacting the given remote address
   * @param remote the remote address
   * @return the local address to be used for the given remote address
   */
  def localAddressForRemote(remote: Address): Address

  /**
   * Start up the transport, i.e. enable incoming connections.
   */
  def start(): Unit

  /**
   * Attempts to shut down a specific client connected to the supplied remote address
   */
  def shutdownClientConnection(address: Address): Unit

  /**
   * Attempts to restart a specific client connected to the supplied remote address, but only if the client is not shut down
   */
  def restartClientConnection(address: Address): Unit

  /**
   * Sends the given message to the recipient supplying the sender if any
   */
  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit

  /**
   * Default implementation both publishes the message to the eventStream as well as logs it using the system logger
   */
  def notifyListeners(message: RemoteLifeCycleEvent): Unit = {
    system.eventStream.publish(message)
    if (logRemoteLifeCycleEvents) log.log(message.logLevel, "{}", message)
  }

  /**
   * Sends a management command to the underlying transport stack. The call returns with a Future that indicates
   * if the command was handled successfully or dropped.
   * @param cmd Command message to send to the transports.
   * @return A Future that indicates when the message was successfully handled or dropped.
   */
  def managementCommand(cmd: Any): Future[Boolean] = { Future.successful(false) }

  /**
   * A Logger that can be used to log issues that may occur
   */
  def log: LoggingAdapter

  /**
   * When this method returns true, some functionality will be turned off for security purposes.
   */
  protected def useUntrustedMode: Boolean

  /**
   * When this method returns true, RemoteLifeCycleEvents will be logged as well as be put onto the eventStream.
   */
  protected def logRemoteLifeCycleEvents: Boolean

  /**
   * Returns a newly created AkkaRemoteProtocol with the given message payload.
   */
  def createMessageSendEnvelope(rmp: RemoteMessageProtocol): AkkaRemoteProtocol =
    AkkaRemoteProtocol.newBuilder.setPayload(rmp.toByteString).build

  /**
   * Returns a newly created AkkaRemoteProtocol with the given control payload.
   */
  def createControlEnvelope(rcp: RemoteControlProtocol): AkkaRemoteProtocol =
    AkkaRemoteProtocol.newBuilder.setInstruction(rcp).build

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol =
    ActorRefProtocol.newBuilder.setPath(actor.path.toStringWithAddress(defaultAddress)).build

  /**
   * Returns a new RemoteMessageProtocol containing the serialized representation of the given parameters.
   */
  def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {
    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    Serialization.currentTransportAddress.withValue(defaultAddress) {
      messageBuilder.setMessage(MessageSerializer.serialize(system, message.asInstanceOf[AnyRef]))
    }

    messageBuilder
  }

  /**
   * Call this method with an inbound RemoteMessage and this will take care of security (see: "useUntrustedMode")
   * as well as making sure that the message ends up at its destination (best effort).
   * There is also a fair amount of logging produced by this method, which is good for debugging.
   */
  def receiveMessage(remoteMessage: RemoteMessage): Unit = {
    val remoteDaemon = provider.remoteDaemon

    remoteMessage.recipient match {
      case `remoteDaemon` ⇒
        if (useUntrustedMode) log.debug("dropping daemon message in untrusted mode")
        else {
          if (provider.remoteSettings.LogReceive) log.debug("received daemon message {}", remoteMessage)
          remoteMessage.payload match {
            case m @ (_: DaemonMsg | _: Terminated) ⇒
              try remoteDaemon ! m catch {
                case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m, remoteMessage.sender)
              }
            case x ⇒ log.debug("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
          }
        }
      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received local message {}", remoteMessage)
        remoteMessage.payload match {
          case msg: PossiblyHarmful if useUntrustedMode ⇒ log.debug("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type {}", msg.getClass)
          case msg: SystemMessage                       ⇒ l.sendSystemMessage(msg)
          case msg                                      ⇒ l.!(msg)(remoteMessage.sender)
        }
      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal && !useUntrustedMode ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received remote-destined message {}", remoteMessage)
        remoteMessage.originalReceiver match {
          case AddressFromURIString(address) if provider.transport.addresses(address) ⇒
            // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case r ⇒
            log.debug("dropping message {} for non-local recipient {} arriving at {} inbound addresses are {}",
              remoteMessage.payloadClass, r, addresses, provider.transport.addresses)
        }
      case r ⇒
        log.debug("dropping message {} for unknown recipient {} arriving at {} inbound addresses are {}",
          remoteMessage.payloadClass, r, addresses, provider.transport.addresses)
    }
  }
}

/**
 * RemoteMessage is a wrapper around a message that has come in over the wire,
 * it allows to easily obtain references to the deserialized message, its intended recipient
 * and the sender.
 */
class RemoteMessage(input: RemoteMessageProtocol, system: ExtendedActorSystem) {
  /**
   * Returns a String-representation of the ActorPath that this RemoteMessage is destined for
   */
  def originalReceiver: String = input.getRecipient.getPath

  /**
   * Returns an Option with the String representation of the ActorPath of the Actor who is the sender of this message
   */
  def originalSender: Option[String] = if (input.hasSender) Some(input.getSender.getPath) else None

  /**
   * Returns a reference to the Actor that sent this message, or DeadLetterActorRef if not present or found.
   */
  lazy val sender: ActorRef =
    if (input.hasSender) system.provider.actorFor(system.provider.rootGuardian, input.getSender.getPath)
    else system.deadLetters

  /**
   * Returns a reference to the Actor that this message is destined for.
   * In case this returns a DeadLetterActorRef, you have access to the path using the "originalReceiver" method.
   */
  lazy val recipient: InternalActorRef = system.provider.actorFor(system.provider.rootGuardian, originalReceiver)

  /**
   * Returns the message
   */
  lazy val payload: AnyRef = MessageSerializer.deserialize(system, input.getMessage)

  def payloadClass: Class[_] = if (payload eq null) null else payload.getClass

  /**
   * Returns a String representation of this RemoteMessage, intended for debugging purposes.
   */
  override def toString: String = "RemoteMessage: " + payloadClass + " to " + recipient + "<+{" + originalReceiver + "} from " + sender
}
