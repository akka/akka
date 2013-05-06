/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor.ActorRef
import akka.io.Inet.SocketOption
import akka.io.SelectionHandler._
import akka.io.Tcp._
import java.io.IOException
import java.nio.channels.{ SelectionKey, SocketChannel }
import scala.collection.immutable

/**
 * An actor handling the connection state machine for an outgoing connection
 * to be established.
 *
 * INTERNAL API
 */
private[io] class TcpOutgoingConnection(_tcp: TcpExt,
                                        channelRegistry: ChannelRegistry,
                                        commander: ActorRef,
                                        connect: Connect)
  extends TcpConnection(_tcp, TcpOutgoingConnection.newSocketChannel()) {

  import connect._

  context.watch(commander) // sign death pact

  localAddress.foreach(channel.socket.bind)
  options.foreach(_.beforeConnect(channel.socket))
  channelRegistry.register(channel, SelectionKey.OP_CONNECT)

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      log.debug("Attempting connection to [{}]", remoteAddress)
      if (channel.connect(remoteAddress))
        completeConnect(registration, commander, options)
      else
        context.become(connecting(registration, commander, options))
  }

  def connecting(registration: ChannelRegistration, commander: ActorRef,
                 options: immutable.Traversable[SocketOption]): Receive = {
    case ChannelConnectable ⇒
      try {
        val connected = channel.finishConnect()
        assert(connected, "Connectable channel failed to connect")
        log.debug("Connection established")
        completeConnect(registration, commander, options)
      } catch {
        case e: IOException ⇒
          if (tcp.Settings.TraceLogging) log.debug("Could not establish connection due to {}", e)
          closedMessage = TcpConnection.CloseInformation(Set(commander), connect.failureMessage)
          throw e
      }
  }
}

/**
 * INTERNAL API
 */
private[io] object TcpOutgoingConnection {
  private def newSocketChannel() = {
    val channel = SocketChannel.open()
    channel.configureBlocking(false)
    channel
  }
}
