/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.IOException
import java.nio.channels.{ SelectionKey, SocketChannel }
import scala.collection.immutable
import akka.actor.ActorRef
import akka.io.SelectionHandler._
import akka.io.Inet.SocketOption
import akka.io.Tcp._

/**
 * An actor handling the connection state machine for an outgoing connection
 * to be established.
 */
private[io] class TcpOutgoingConnection(_tcp: TcpExt,
                                        commander: ActorRef,
                                        remoteAddress: InetSocketAddress,
                                        localAddress: Option[InetSocketAddress],
                                        options: immutable.Traversable[SocketOption])
  extends TcpConnection(TcpOutgoingConnection.newSocketChannel(), _tcp) {

  context.watch(commander) // sign death pact

  localAddress.foreach(channel.socket.bind)
  options.foreach(_.beforeConnect(channel.socket))
  selector ! RegisterChannel(channel, SelectionKey.OP_CONNECT)

  def receive: Receive = {
    case ChannelRegistered ⇒
      log.debug("Attempting connection to {}", remoteAddress)
      if (channel.connect(remoteAddress))
        completeConnect(commander, options)
      else {
        context.become(connecting(commander, options))
      }
  }

  def connecting(commander: ActorRef, options: immutable.Traversable[SocketOption]): Receive = {
    case ChannelConnectable ⇒
      try {
        val connected = channel.finishConnect()
        assert(connected, "Connectable channel failed to connect")
        log.debug("Connection established")
        completeConnect(commander, options)
      } catch {
        case e: IOException ⇒ handleError(commander, e)
      }
  }

}

object TcpOutgoingConnection {
  private def newSocketChannel() = {
    val channel = SocketChannel.open()
    channel.configureBlocking(false)
    channel
  }
}
