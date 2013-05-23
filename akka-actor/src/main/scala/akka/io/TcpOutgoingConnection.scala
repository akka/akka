/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor.{ ReceiveTimeout, ActorRef }
import akka.io.Inet.SocketOption
import akka.io.SelectionHandler._
import akka.io.Tcp._
import java.io.IOException
import java.nio.channels.{ SelectionKey, SocketChannel }
import scala.collection.immutable
import scala.concurrent.duration.Duration
import java.net.{ ConnectException, SocketTimeoutException }

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
  extends TcpConnection(_tcp, SocketChannel.open().configureBlocking(false).asInstanceOf[SocketChannel]) {

  import connect._

  context.watch(commander) // sign death pact

  localAddress.foreach(channel.socket.bind)
  options.foreach(_.beforeConnect(channel.socket))
  channelRegistry.register(channel, SelectionKey.OP_CONNECT)
  timeout foreach context.setReceiveTimeout //Initiate connection timeout if supplied

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      log.debug("Attempting connection to [{}]", remoteAddress)
      if (channel.connect(remoteAddress))
        completeConnect(registration, commander, options)
      else
        context.become(connecting(registration, commander, options))
  }

  def connecting(registration: ChannelRegistration, commander: ActorRef,
                 options: immutable.Traversable[SocketOption]): Receive =
    {
      case ChannelConnectable ⇒
        if (timeout.isDefined) context.setReceiveTimeout(Duration.Undefined) // Clear the timeout
        try {
          channel.finishConnect() || (throw new ConnectException(s"Connection to [$remoteAddress] failed"))
          log.debug("Connection established to [{}]", remoteAddress)
          completeConnect(registration, commander, options)
        } catch {
          case e: IOException ⇒
            if (tcp.Settings.TraceLogging) log.debug("Could not establish connection due to {}", e)
            closedMessage = TcpConnection.CloseInformation(Set(commander), connect.failureMessage)
            throw e
        }

      case ReceiveTimeout ⇒
        if (timeout.isDefined) context.setReceiveTimeout(Duration.Undefined) // Clear the timeout
        val failure = new SocketTimeoutException(s"Connection to [$remoteAddress] timed out")
        if (tcp.Settings.TraceLogging) log.debug("Could not establish connection due to {}", failure)
        closedMessage = TcpConnection.CloseInformation(Set(commander), connect.failureMessage)
        throw failure
    }
}
