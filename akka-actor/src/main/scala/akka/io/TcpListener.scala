/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.{ SelectionKey, ServerSocketChannel }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.actor.{ Props, ActorLogging, ActorRef, Actor }
import akka.io.SelectionHandler._
import Tcp._

private[io] class TcpListener(selectorRouter: ActorRef,
                              handler: ActorRef,
                              endpoint: InetSocketAddress,
                              backlog: Int,
                              bindCommander: ActorRef,
                              tcp: TcpExt,
                              options: immutable.Traversable[SocketOption]) extends Actor with ActorLogging {

  def selector: ActorRef = context.parent
  import tcp.Settings._

  context.watch(handler) // sign death pact
  val channel = {
    val serverSocketChannel = ServerSocketChannel.open
    serverSocketChannel.configureBlocking(false)
    val socket = serverSocketChannel.socket
    options.foreach(_.beforeBind(socket))
    socket.bind(endpoint, backlog) // will blow up the actor constructor if the bind fails
    serverSocketChannel
  }
  context.parent ! RegisterChannel(channel, SelectionKey.OP_ACCEPT)
  log.debug("Successfully bound to {}", endpoint)

  def receive: Receive = {
    case KickStartDone ⇒
      bindCommander ! Bound
      context.become(bound)
  }

  def bound: Receive = {
    case ChannelAcceptable ⇒
      acceptAllPending(BatchAcceptLimit)

    //    case CommandFailed(RegisterIncomingConnection(socketChannel, _, _)) ⇒
    //      log.warning("Could not register incoming connection since selector capacity limit is reached, closing connection")
    //      try socketChannel.close()
    //      catch {
    //        case NonFatal(e) ⇒ log.error(e, "Error closing channel")
    //      }

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
        //selectorRouter ! RegisterIncomingConnection(socketChannel, handler, options)
        // FIXME null is not nice. There is no explicit API command here
        selectorRouter ! KickStartCommand(null, context.system.deadLetters, Props(new TcpIncomingConnection(socketChannel, tcp, handler, options)))
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
