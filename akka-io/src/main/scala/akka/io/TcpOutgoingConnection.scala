/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.IOException
import java.nio.channels.SocketChannel
import scala.collection.immutable
import akka.actor.ActorRef
import Tcp._

/**
 * An actor handling the connection state machine for an outgoing connection
 * to be established.
 */
class TcpOutgoingConnection(_selector: ActorRef,
                            _tcp: TcpExt,
                            commander: ActorRef,
                            remoteAddress: InetSocketAddress,
                            localAddress: Option[InetSocketAddress],
                            options: Traversable[SocketOption])
  extends TcpConnection(_selector, TcpOutgoingConnection.newSocketChannel(), _tcp) {

  context.watch(commander) // sign death pact

  localAddress.foreach(channel.socket.bind)
  options.foreach(_.beforeConnect(channel.socket))

  log.debug("Attempting connection to {}", remoteAddress)
  if (channel.connect(remoteAddress))
    completeConnect(commander, options)
  else {
    selector ! RegisterOutgoingConnection(channel)
    context.become(connecting(commander, options))
    Seq(1,2,3)
  }

  def receive: Receive = PartialFunction.empty

  def connecting(commander: ActorRef, options: Traversable[SocketOption]): Receive = {
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
