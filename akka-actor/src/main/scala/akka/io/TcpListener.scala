/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.{ SocketChannel, SelectionKey, ServerSocketChannel }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.actor.{ Props, ActorLogging, ActorRef, Actor }
import akka.io.SelectionHandler._
import akka.io.Inet.SocketOption
import akka.io.Tcp._
import akka.io.IO.HasFailureMessage

/**
 * INTERNAL API
 */
private[io] object TcpListener {

  case class RegisterIncoming(channel: SocketChannel) extends HasFailureMessage {
    def failureMessage = FailedRegisterIncoming(channel)
  }

  case class FailedRegisterIncoming(channel: SocketChannel)

}

/**
 * INTERNAL API
 */
private[io] class TcpListener(val selectorRouter: ActorRef,
                              val tcp: TcpExt,
                              val bindCommander: ActorRef,
                              val bind: Bind) extends Actor with ActorLogging {

  def selector: ActorRef = context.parent
  import TcpListener._
  import tcp.Settings._
  import bind._

  context.watch(handler) // sign death pact
  val channel = {
    val serverSocketChannel = ServerSocketChannel.open
    serverSocketChannel.configureBlocking(false)
    val socket = serverSocketChannel.socket
    options.foreach(_.beforeServerSocketBind(socket))
    try socket.bind(endpoint, backlog)
    catch {
      case NonFatal(e) ⇒
        bindCommander ! CommandFailed(bind)
        log.error(e, "Bind failed for TCP channel on endpoint [{}]", endpoint)
        context.stop(self)
    }
    serverSocketChannel
  }
  context.parent ! RegisterChannel(channel, SelectionKey.OP_ACCEPT)
  log.debug("Successfully bound to {}", endpoint)

  def receive: Receive = {
    case ChannelRegistered ⇒
      bindCommander ! Bound
      context.become(bound)
  }

  def bound: Receive = {
    case ChannelAcceptable ⇒
      acceptAllPending(BatchAcceptLimit)

    case FailedRegisterIncoming(socketChannel) ⇒
      log.warning("Could not register incoming connection since selector capacity limit is reached, closing connection")
      try socketChannel.close()
      catch {
        case NonFatal(e) ⇒ log.error(e, "Error closing channel")
      }

    case Unbind ⇒
      log.debug("Unbinding endpoint {}", endpoint)
      channel.close()
      sender ! Unbound
      log.debug("Unbound endpoint {}, stopping listener", endpoint)
      context.stop(self)
  }

  @tailrec final def acceptAllPending(limit: Int): Unit =
    if (limit > 0) {
      val socketChannel =
        try channel.accept()
        catch {
          case NonFatal(e) ⇒ log.error(e, "Accept error: could not accept new connection due to {}", e); null
        }
      if (socketChannel != null) {
        log.debug("New connection accepted")
        socketChannel.configureBlocking(false)
        selectorRouter ! WorkerForCommand(RegisterIncoming(socketChannel), self, Props(new TcpIncomingConnection(socketChannel, tcp, handler, options)))
        acceptAllPending(limit - 1)
      }
    } else context.parent ! AcceptInterest

  override def postStop() {
    try {
      if (channel.isOpen) {
        log.debug("Closing serverSocketChannel after being stopped")
        channel.close()
      }
    } catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing ServerSocketChannel")
    }
  }

}
