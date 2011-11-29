/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.AkkaException
import scala.reflect.BeanProperty
import java.io.{ PrintWriter, PrintStream }
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.InetAddress

object RemoteAddress {
  def apply(system: String, host: String, port: Int) = {
    val ip = InetAddress.getByName(host)
    new RemoteAddress(system, host, ip, port)
  }
}

case class RemoteAddress(system: String, host: String, ip: InetAddress, port: Int) extends Address {
  def protocol = "akka"
  @transient
  lazy val hostPort = system + "@" + host + ":" + port
}

object RemoteActorPath {
  def unapply(addr: String): Option[(RemoteAddress, Iterable[String])] = {
    try {
      val uri = new URI(addr)
      if (uri.getScheme != "akka") return None
      if (uri.getUserInfo == null) return None
      if (uri.getHost == null) return None
      if (uri.getPort == -1) return None
      if (uri.getPath == null) return None
      Some(RemoteAddress(uri.getUserInfo, uri.getHost, uri.getPort), uri.getPath.split("/").drop(1))
    } catch {
      case _: URISyntaxException ⇒ None
    }
  }
}

class RemoteException(message: String) extends AkkaException(message)

trait RemoteModule {
  protected[akka] def notifyListeners(message: RemoteLifeCycleEvent): Unit
}

/**
 * Remote life-cycle events.
 */
sealed trait RemoteLifeCycleEvent

/**
 * Life-cycle events for RemoteClient.
 */
trait RemoteClientLifeCycleEvent extends RemoteLifeCycleEvent {
  def remoteAddress: RemoteAddress
}

case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @BeanProperty remote: RemoteSupport,
  @BeanProperty remoteAddress: RemoteAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientDisconnected(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty remoteAddress: RemoteAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientConnected(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty remoteAddress: RemoteAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientStarted(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty remoteAddress: RemoteAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientShutdown(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty remoteAddress: RemoteAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty remote: RemoteSupport,
  @BeanProperty remoteAddress: RemoteAddress) extends RemoteClientLifeCycleEvent

/**
 *  Life-cycle events for RemoteServer.
 */
trait RemoteServerLifeCycleEvent extends RemoteLifeCycleEvent

case class RemoteServerStarted(
  @BeanProperty remote: RemoteSupport) extends RemoteServerLifeCycleEvent
case class RemoteServerShutdown(
  @BeanProperty remote: RemoteSupport) extends RemoteServerLifeCycleEvent
case class RemoteServerError(
  @BeanProperty val cause: Throwable,
  @BeanProperty remote: RemoteSupport) extends RemoteServerLifeCycleEvent
case class RemoteServerClientConnected(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty val clientAddress: Option[RemoteAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerClientDisconnected(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty val clientAddress: Option[RemoteAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerClientClosed(
  @BeanProperty remote: RemoteSupport,
  @BeanProperty val clientAddress: Option[RemoteAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty server: RemoteSupport,
  @BeanProperty remoteAddress: Option[RemoteAddress]) extends RemoteServerLifeCycleEvent

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka] (
  message: String,
  @BeanProperty val client: RemoteSupport,
  val remoteAddress: RemoteAddress, cause: Throwable = null) extends AkkaException(message, cause)

/**
 * Thrown when the remote server actor dispatching fails for some reason.
 */
class RemoteServerException private[akka] (message: String) extends AkkaException(message)

/**
 * Thrown when a remote exception sent over the wire cannot be loaded and instantiated
 */
case class CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException private[akka] (cause: Throwable, originalClassName: String, originalMessage: String)
  extends AkkaException("\nParsingError[%s]\nOriginalException[%s]\nOriginalMessage[%s]"
    .format(cause.toString, originalClassName, originalMessage)) {
  override def printStackTrace = cause.printStackTrace
  override def printStackTrace(printStream: PrintStream) = cause.printStackTrace(printStream)
  override def printStackTrace(printWriter: PrintWriter) = cause.printStackTrace(printWriter)
}

abstract class RemoteSupport(val system: ActorSystem) {
  /**
   * Shuts down the remoting
   */
  def shutdown(): Unit

  /**
   *  Gets the name of the server instance
   */
  def name: String

  /**
   *  Starts up the remoting
   */
  def start(loader: Option[ClassLoader]): Unit

  /**
   * Shuts down a specific client connected to the supplied remote address returns true if successful
   */
  def shutdownClientConnection(address: RemoteAddress): Boolean

  /**
   * Restarts a specific client connected to the supplied remote address, but only if the client is not shut down
   */
  def restartClientConnection(address: RemoteAddress): Boolean

  /** Methods that needs to be implemented by a transport **/

  protected[akka] def send(message: Any,
                           senderOption: Option[ActorRef],
                           remoteAddress: RemoteAddress,
                           recipient: ActorRef,
                           loader: Option[ClassLoader]): Unit

  protected[akka] def notifyListeners(message: RemoteLifeCycleEvent): Unit = system.eventStream.publish(message)

  override def toString = name
}
