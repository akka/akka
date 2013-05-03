/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.{ SocketChannel, SelectionKey, ServerSocketChannel }
import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor.{ Props, ActorLogging, ActorRef, Actor }
import akka.io.SelectionHandler._
import akka.io.Tcp._
import akka.io.IO.HasFailureMessage
import java.net.InetSocketAddress

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
  import TcpListener._
  import tcp.Settings._

  context.watch(bind.handler) // sign death pact

  val channel = ServerSocketChannel.open
  channel.configureBlocking(false)

  val localAddress =
    try {
      val socket = channel.socket
      bind.options.foreach(_.beforeServerSocketBind(socket))
      socket.bind(bind.localAddress, bind.backlog)
      val ret = socket.getLocalSocketAddress match {
        case isa: InetSocketAddress ⇒ isa
        case x                      ⇒ throw new IllegalArgumentException(s"bound to unknown SocketAddress [$x]")
      }
      context.parent ! RegisterChannel(channel, SelectionKey.OP_ACCEPT)
      log.debug("Successfully bound to {}", ret)
      ret
    } catch {
      case NonFatal(e) ⇒
        bindCommander ! bind.failureMessage
        log.error(e, "Bind failed for TCP channel on endpoint [{}]", bind.localAddress)
        context.stop(self)
    }

  override def supervisorStrategy = IO.connectionSupervisorStrategy

  def receive: Receive = {
    case ChannelRegistered ⇒
      bindCommander ! Bound(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
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
      log.debug("Unbinding endpoint {}", localAddress)
      channel.close()
      sender ! Unbound
      log.debug("Unbound endpoint {}, stopping listener", localAddress)
      context.stop(self)
  }

  @tailrec final def acceptAllPending(limit: Int): Unit = {
    val socketChannel =
      if (limit > 0) {
        try channel.accept()
        catch {
          case NonFatal(e) ⇒ { log.error(e, "Accept error: could not accept new connection due to {}", e); null }
        }
      } else null
    if (socketChannel != null) {
      log.debug("New connection accepted")
      socketChannel.configureBlocking(false)
      selectorRouter ! WorkerForCommand(RegisterIncoming(socketChannel), self, Props(classOf[TcpIncomingConnection], socketChannel, tcp, bind.handler, bind.options))
      acceptAllPending(limit - 1)
    } else context.parent ! AcceptInterest
  }

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
