/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.reflect.BeanProperty

import akka.actor.{ Terminated, LocalRef, InternalActorRef, AutoReceivedMessage, AddressExtractor, Address, ActorSystemImpl, ActorSystem, ActorRef }
import akka.dispatch.SystemMessage
import akka.event.{ LoggingAdapter, Logging }
import akka.remote.RemoteProtocol.{ RemoteMessageProtocol, RemoteControlProtocol, AkkaRemoteProtocol, ActorRefProtocol }
import akka.AkkaException

/**
 * Remote life-cycle events.
 */
sealed trait RemoteLifeCycleEvent {
  def logLevel: Logging.LogLevel
}

/**
 * Life-cycle events for RemoteClient.
 */
trait RemoteClientLifeCycleEvent extends RemoteLifeCycleEvent {
  def remoteAddress: Address
}

case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.ErrorLevel
  override def toString =
    "RemoteClientError@" +
      remoteAddress +
      ": Error[" +
      (if (cause ne null) cause.getClass.getName + ": " + cause.getMessage else "unknown") +
      "]"
}

case class RemoteClientDisconnected(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.DebugLevel
  override def toString =
    "RemoteClientDisconnected@" + remoteAddress
}

case class RemoteClientConnected(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.DebugLevel
  override def toString =
    "RemoteClientConnected@" + remoteAddress
}

case class RemoteClientStarted(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.InfoLevel
  override def toString =
    "RemoteClientStarted@" + remoteAddress
}

case class RemoteClientShutdown(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.InfoLevel
  override def toString =
    "RemoteClientShutdown@" + remoteAddress
}

case class RemoteClientWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.WarningLevel
  override def toString =
    "RemoteClientWriteFailed@" +
      remoteAddress +
      ": MessageClass[" +
      (if (request ne null) request.getClass.getName else "no message") +
      "] Error[" +
      (if (cause ne null) cause.getClass.getName + ": " + cause.getMessage else "unknown") +
      "]"
}

/**
 *  Life-cycle events for RemoteServer.
 */
trait RemoteServerLifeCycleEvent extends RemoteLifeCycleEvent

case class RemoteServerStarted(
  @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.InfoLevel
  override def toString =
    "RemoteServerStarted@" + remote
}

case class RemoteServerShutdown(
  @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.InfoLevel
  override def toString =
    "RemoteServerShutdown@" + remote
}

case class RemoteServerError(
  @BeanProperty val cause: Throwable,
  @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.ErrorLevel
  override def toString =
    "RemoteServerError@" +
      remote +
      ": Error[" +
      (if (cause ne null) cause.getClass.getName + ": " + cause.getMessage else "unknown") +
      "]"
}

case class RemoteServerClientConnected(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.DebugLevel
  override def toString =
    "RemoteServerClientConnected@" +
      remote +
      ": Client[" +
      (if (clientAddress.isDefined) clientAddress.get else "no address") +
      "]"
}

case class RemoteServerClientDisconnected(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.DebugLevel
  override def toString =
    "RemoteServerClientDisconnected@" +
      remote +
      ": Client[" +
      (if (clientAddress.isDefined) clientAddress.get else "no address") +
      "]"
}

case class RemoteServerClientClosed(
  @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.DebugLevel
  override def toString =
    "RemoteServerClientClosed@" +
      remote +
      ": Client[" +
      (if (clientAddress.isDefined) clientAddress.get else "no address") +
      "]"
}

case class RemoteServerWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.WarningLevel
  override def toString =
    "RemoteServerWriteFailed@" +
      remote +
      ": ClientAddress[" +
      remoteAddress +
      "] MessageClass[" +
      (if (request ne null) request.getClass.getName else "no message") +
      "] Error[" +
      (if (cause ne null) cause.getClass.getName + ": " + cause.getMessage else "unknown") +
      "]"
}

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka] (
  message: String,
  @BeanProperty val client: RemoteTransport,
  val remoteAddress: Address, cause: Throwable = null) extends AkkaException(message, cause)

class RemoteTransportException(message: String, cause: Throwable) extends AkkaException(message, cause)

abstract class RemoteTransport {
  /**
   * Shuts down the remoting
   */
  def shutdown(): Unit

  /**
   * Address to be used in RootActorPath of refs generated for this transport.
   */
  def address: Address

  /**
   * The actor system, for which this transport is instantiated. Will publish to its eventStream.
   */
  def system: ActorSystem

  /**
   *  Starts up the remoting
   */
  def start(system: ActorSystemImpl, provider: RemoteActorRefProvider): Unit

  /**
   * Shuts down a specific client connected to the supplied remote address returns true if successful
   */
  def shutdownClientConnection(address: Address): Boolean

  /**
   * Restarts a specific client connected to the supplied remote address, but only if the client is not shut down
   */
  def restartClientConnection(address: Address): Boolean

  /** Methods that needs to be implemented by a transport **/

  protected[akka] def send(message: Any,
                           senderOption: Option[ActorRef],
                           recipient: RemoteActorRef,
                           loader: Option[ClassLoader]): Unit

  protected[akka] def notifyListeners(message: RemoteLifeCycleEvent): Unit = {
    system.eventStream.publish(message)
    system.log.log(message.logLevel, "REMOTE: {}", message)
  }

  override def toString = address.toString
}

class RemoteMessage(input: RemoteMessageProtocol, system: ActorSystemImpl, classLoader: Option[ClassLoader]) {

  def originalReceiver = input.getRecipient.getPath

  lazy val sender: ActorRef =
    if (input.hasSender) system.provider.actorFor(system.provider.rootGuardian, input.getSender.getPath)
    else system.deadLetters

  lazy val recipient: InternalActorRef = system.provider.actorFor(system.provider.rootGuardian, originalReceiver)

  lazy val payload: AnyRef = MessageSerializer.deserialize(system, input.getMessage, classLoader)

  override def toString = "RemoteMessage: " + payload + " to " + recipient + "<+{" + originalReceiver + "} from " + sender
}

trait RemoteMarshallingOps {

  def log: LoggingAdapter

  def system: ActorSystemImpl

  def provider: RemoteActorRefProvider

  protected def useUntrustedMode: Boolean

  def createMessageSendEnvelope(rmp: RemoteMessageProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setMessage(rmp)
    arp.build
  }

  def createControlEnvelope(rcp: RemoteControlProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setInstruction(rcp)
    arp.build
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol = {
    ActorRefProtocol.newBuilder.setPath(actor.path.toString).build
  }

  def createRemoteMessageProtocolBuilder(
    recipient: ActorRef,
    message: Any,
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    messageBuilder.setMessage(MessageSerializer.serialize(system, message.asInstanceOf[AnyRef]))

    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }

  def receiveMessage(remoteMessage: RemoteMessage) {
    log.debug("received message {}", remoteMessage)

    val remoteDaemon = provider.remoteDaemon

    remoteMessage.recipient match {
      case `remoteDaemon` ⇒
        remoteMessage.payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m, remoteMessage.sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
        }
      case l: LocalRef ⇒
        remoteMessage.payload match {
          case msg: SystemMessage ⇒
            if (useUntrustedMode)
              throw new SecurityException("RemoteModule server is operating is untrusted mode, can not send system message")
            else l.sendSystemMessage(msg)
          case _: AutoReceivedMessage if (useUntrustedMode) ⇒
            throw new SecurityException("RemoteModule server is operating is untrusted mode, can not pass on a AutoReceivedMessage to the remote actor")
          case m ⇒ l.!(m)(remoteMessage.sender)
        }
      case r: RemoteActorRef ⇒
        remoteMessage.originalReceiver match {
          case AddressExtractor(address) if address == provider.transport.address ⇒
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case r ⇒ log.error("dropping message {} for non-local recipient {}", remoteMessage.payload, r)
        }
      case r ⇒ log.error("dropping message {} for non-local recipient {}", remoteMessage.payload, r)
    }
  }
}
