/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.AkkaException

import scala.reflect.BeanProperty
import java.io.{ PrintWriter, PrintStream }

import java.net.InetSocketAddress

object RemoteAddress {
  def apply(host: String, port: Int): RemoteAddress = apply(new InetSocketAddress(host, port))

  def apply(inetAddress: InetSocketAddress): RemoteAddress = inetAddress match {
    case null ⇒ null
    case inet ⇒
      val host = inet.getAddress match {
        case null  ⇒ inet.getHostName //Fall back to given name
        case other ⇒ other.getHostAddress
      }
      val portNo = inet.getPort
      RemoteAddress(portNo, host)
  }

  def apply(address: String): RemoteAddress = {
    val index = address.indexOf(":")
    if (index < 1) throw new IllegalArgumentException(
      "Remote address must be a string on the format [\"hostname:port\"], was [" + address + "]")
    val hostname = address.substring(0, index)
    val port = address.substring(index + 1, address.length).toInt
    apply(new InetSocketAddress(hostname, port)) // want the fallback in this method
  }
}

case class RemoteAddress private[remote] (port: Int, hostname: String) {
  @transient
  override lazy val toString = "" + hostname + ":" + port
}

object LocalOnly extends RemoteAddress(0, "local")

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
