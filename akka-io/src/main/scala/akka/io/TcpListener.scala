/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.actor.{ ActorLogging, ActorRef, Actor }
import Tcp._

class TcpListener(manager: ActorRef,
                  selector: ActorRef,
                  handler: ActorRef,
                  endpoint: InetSocketAddress,
                  backlog: Int,
                  bindCommander: ActorRef,
                  options: immutable.Seq[SocketOption]) extends Actor with ActorLogging {

  val batchAcceptLimit = Tcp(context.system).Settings.BatchAcceptLimit
  val channel = {
    val serverSocketChannel = ServerSocketChannel.open
    serverSocketChannel.configureBlocking(false)
    val socket = serverSocketChannel.socket
    options.foreach(_.beforeBind(socket))
    socket.bind(endpoint, backlog) // will blow up the actor constructor if the bind fails
    serverSocketChannel
  }
  selector ! RegisterServerSocketChannel(channel)
  context.watch(bindCommander) // sign death pact
  log.debug("Successfully bound to {}", endpoint)

  def receive: Receive = {
    case Bound ⇒
      bindCommander ! Bound
      context.become(bound)
  }

  def bound: Receive = {
    case ChannelAcceptable ⇒
      acceptAllPending(batchAcceptLimit)

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
        manager ! RegisterIncomingConnection(socketChannel, handler, options)
        selector ! AcceptInterest
        acceptAllPending(limit - 1)
      }
    }

  override def postStop() {
    try channel.close()
    catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing ServerSocketChannel")
    }
  }

}
