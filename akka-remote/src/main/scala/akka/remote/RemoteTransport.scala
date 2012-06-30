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
}

/**
 * A RemoteClientError is a general error that is thrown within or from a RemoteClient
 */
case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.ErrorLevel
  override def toString: String = "RemoteClientError@" + remoteAddress + ": Error[" + cause + "]"
}

/**
 * RemoteClientDisconnected is published when a RemoteClient's connection is disconnected
 */
case class RemoteClientDisconnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String = "RemoteClientDisconnected@" + remoteAddress
}

/**
 * RemoteClientConnected is published when a RemoteClient's connection is established
 */
case class RemoteClientConnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.DebugLevel
  override def toString: String = "RemoteClientConnected@" + remoteAddress
}

/**
 * RemoteClientStarted is published when a RemoteClient has started up
 */
case class RemoteClientStarted(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel: Logging.LogLevel = Logging.InfoLevel
  override def toString: String = "RemoteClientStarted@" + remoteAddress
}

/**
 * RemoteClientShutdown is published when a RemoteClient has shut down
 */
case class RemoteClientShutdown(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
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
  def address: Address

  /**
   * Start up the transport, i.e. enable incoming connections.
   */
  def start(): Unit

  /**
   * Shuts down a specific client connected to the supplied remote address returns true if successful
   */
  def shutdownClientConnection(address: Address): Boolean

  /**
   * Restarts a specific client connected to the supplied remote address, but only if the client is not shut down
   */
  def restartClientConnection(address: Address): Boolean

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
   * Returns this RemoteTransports Address' textual representation
   */
  override def toString: String = address.toString

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
    AkkaRemoteProtocol.newBuilder.setMessage(rmp).build

  /**
   * Returns a newly created AkkaRemoteProtocol with the given control payload.
   */
  def createControlEnvelope(rcp: RemoteControlProtocol): AkkaRemoteProtocol =
    AkkaRemoteProtocol.newBuilder.setInstruction(rcp).build

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol =
    ActorRefProtocol.newBuilder.setPath(actor.path.toStringWithAddress(address)).build

  /**
   * Returns a new RemoteMessageProtocol containing the serialized representation of the given parameters.
   */
  def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {
    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    Serialization.currentTransportAddress.withValue(address) {
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
        if (provider.remoteSettings.LogReceive) log.debug("received daemon message {}", remoteMessage)
        remoteMessage.payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m, remoteMessage.sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
        }
      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received local message {}", remoteMessage)
        remoteMessage.payload match {
          case msg: PossiblyHarmful if useUntrustedMode ⇒ log.warning("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type {}", msg.getClass)
          case msg: SystemMessage                       ⇒ l.sendSystemMessage(msg)
          case msg                                      ⇒ l.!(msg)(remoteMessage.sender)
        }
      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received remote-destined message {}", remoteMessage)
        remoteMessage.originalReceiver match {
          case AddressFromURIString(address) if address == provider.transport.address ⇒
            // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case r ⇒ log.error("dropping message {} for non-local recipient {} arriving at {} inbound address is {}", remoteMessage.payload, r, address, provider.transport.address)
        }
      case r ⇒ log.error("dropping message {} for unknown recipient {} arriving at {} inbound address is {}", remoteMessage.payload, r, address, provider.transport.address)
    }
  }
}

/**
 * RemoteMessage is a wrapper around a message that has come in over the wire,
 * it allows to easily obtain references to the deserialized message, its intended recipient
 * and the sender.
 */
class RemoteMessage(val input: RemoteMessageProtocol, system: ExtendedActorSystem) {
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

  /**
   * Returns a String representation of this RemoteMessage, intended for debugging purposes.
   */
  override def toString: String = "RemoteMessage: " + payload + " to " + recipient + "<+{" + originalReceiver + "} from " + sender
}
