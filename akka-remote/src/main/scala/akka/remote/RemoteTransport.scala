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

case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.ErrorLevel

  override def toString =
    "RemoteClientError@" + remoteAddress + ": Error[" + cause + "]"
}

case class RemoteClientDisconnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.DebugLevel

  override def toString =
    "RemoteClientDisconnected@" + remoteAddress
}

case class RemoteClientConnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.DebugLevel

  override def toString =
    "RemoteClientConnected@" + remoteAddress
}

case class RemoteClientStarted(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.InfoLevel

  override def toString =
    "RemoteClientStarted@" + remoteAddress
}

case class RemoteClientShutdown(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.InfoLevel

  override def toString =
    "RemoteClientShutdown@" + remoteAddress
}

case class RemoteClientWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty remoteAddress: Address) extends RemoteClientLifeCycleEvent {
  override def logLevel = Logging.WarningLevel

  override def toString =
    "RemoteClientWriteFailed@" + remoteAddress +
      ": MessageClass[" + (if (request ne null) request.getClass.getName else "no message") +
      "] Error[" + cause + "]"
}

/**
 *  Life-cycle events for RemoteServer.
 */
trait RemoteServerLifeCycleEvent extends RemoteLifeCycleEvent

case class RemoteServerStarted(
  @transient @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.InfoLevel

  override def toString =
    "RemoteServerStarted@" + remote
}

case class RemoteServerShutdown(
  @transient @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.InfoLevel

  override def toString =
    "RemoteServerShutdown@" + remote
}

case class RemoteServerError(
  @BeanProperty val cause: Throwable,
  @transient @BeanProperty remote: RemoteTransport) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.ErrorLevel

  override def toString =
    "RemoteServerError@" + remote + "] Error[" + cause + "]"
}

case class RemoteServerClientConnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.DebugLevel

  override def toString =
    "RemoteServerClientConnected@" + remote +
      ": Client[" + clientAddress.getOrElse("no address") + "]"
}

case class RemoteServerClientDisconnected(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.DebugLevel

  override def toString =
    "RemoteServerClientDisconnected@" + remote +
      ": Client[" + clientAddress.getOrElse("no address") + "]"
}

case class RemoteServerClientClosed(
  @transient @BeanProperty remote: RemoteTransport,
  @BeanProperty val clientAddress: Option[Address]) extends RemoteServerLifeCycleEvent {
  override def logLevel = Logging.DebugLevel

  override def toString =
    "RemoteServerClientClosed@" + remote +
      ": Client[" + clientAddress.getOrElse("no address") + "]"
}

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka] (
  message: String,
  @transient @BeanProperty val client: RemoteTransport,
  val remoteAddress: Address, cause: Throwable = null) extends AkkaException(message, cause)

class RemoteTransportException(message: String, cause: Throwable) extends AkkaException(message, cause)

/**
 * The remote transport is responsible for sending and receiving messages.
 * Each transport has an address, which it should provide in
 * Serialization.currentTransportAddress (thread-local) while serializing
 * actor references (which might also be part of messages). This address must
 * be available (i.e. fully initialized) by the time the first message is
 * received or when the start() method returns, whatever happens first.
 */
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

  /**Methods that needs to be implemented by a transport **/

  def send(message: Any,
           senderOption: Option[ActorRef],
           recipient: RemoteActorRef): Unit

  def notifyListeners(message: RemoteLifeCycleEvent): Unit = {
    system.eventStream.publish(message)
    system.log.log(message.logLevel, "{}", message)
  }

  override def toString = address.toString
}

class RemoteMessage(input: RemoteMessageProtocol, system: ActorSystemImpl) {

  def originalReceiver = input.getRecipient.getPath

  lazy val sender: ActorRef =
    if (input.hasSender) system.provider.actorFor(system.provider.rootGuardian, input.getSender.getPath)
    else system.deadLetters

  lazy val recipient: InternalActorRef = system.provider.actorFor(system.provider.rootGuardian, originalReceiver)

  lazy val payload: AnyRef = MessageSerializer.deserialize(system, input.getMessage)

  override def toString = "RemoteMessage: " + payload + " to " + recipient + "<+{" + originalReceiver + "} from " + sender
}

trait RemoteMarshallingOps {

  def log: LoggingAdapter

  def system: ActorSystemImpl

  def provider: RemoteActorRefProvider

  def address: Address

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
    ActorRefProtocol.newBuilder.setPath(actor.path.toStringWithAddress(address)).build
  }

  def createRemoteMessageProtocolBuilder(
    recipient: ActorRef,
    message: Any,
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    Serialization.currentTransportAddress.withValue(address) {
      messageBuilder.setMessage(MessageSerializer.serialize(system, message.asInstanceOf[AnyRef]))
    }

    messageBuilder
  }

  def receiveMessage(remoteMessage: RemoteMessage) {
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
      case l: LocalRef ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received local message {}", remoteMessage)
        remoteMessage.payload match {
          case msg: SystemMessage ⇒
            if (useUntrustedMode)
              throw new SecurityException("RemoteModule server is operating is untrusted mode, can not send system message")
            else l.sendSystemMessage(msg)
          case _: AutoReceivedMessage if (useUntrustedMode) ⇒
            throw new SecurityException("RemoteModule server is operating is untrusted mode, can not pass on a AutoReceivedMessage to the remote actor")
          case m ⇒ l.!(m)(remoteMessage.sender)
        }
      case r: RemoteRef ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received remote-destined message {}", remoteMessage)
        remoteMessage.originalReceiver match {
          case AddressFromURIString(address) if address == provider.transport.address ⇒
            // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case ActorPathExtractor(natAddress, elements) if natAddress.system == system.name ⇒
            if (allow(natAddress)) system.actorFor(elements).tell(remoteMessage.payload, remoteMessage.sender)
            else log.error("Firewall: dropping message {} for non-local recipient {} at {} local is {}", remoteMessage.payload, r, address, provider.transport.address)
          case r ⇒ log.error("dropping message {} for non-local recipient {} at {} local is {}", remoteMessage.payload, r, address, provider.transport.address)
        }
      case r ⇒ log.error("dropping message {} for non-local recipient {} of type {}", remoteMessage.payload, r, if (r ne null) r.getClass else "null")
    }
  }

  def allow(natAddress: Address): Boolean = {
    val settings = provider.remoteSettings //have to do this to do the import or else err "stable identifier required"
    import settings.PublicAddresses
    if (natAddress.host.isEmpty || natAddress.port.isEmpty) false //Partial addresses are never OK
    else PublicAddresses.nonEmpty && PublicAddresses.contains(natAddress.host.get + ":" + natAddress.port.get)
  }
}
