/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.io.IOException
import java.nio.channels.{ SelectionKey, SocketChannel }
import java.net.ConnectException
import scala.collection.immutable
import scala.concurrent.duration.Duration
import akka.actor.{ ReceiveTimeout, ActorRef }
import akka.io.Inet.SocketOption
import akka.io.TcpConnection.CloseInformation
import akka.io.SelectionHandler._
import akka.io.Tcp._

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
                 options: immutable.Traversable[SocketOption]): Receive = {
    def stop(): Unit = stopWith(CloseInformation(Set(commander), connect.failureMessage))

    {
      case ChannelConnectable ⇒
        if (timeout.isDefined) context.setReceiveTimeout(Duration.Undefined) // Clear the timeout
        try {
          channel.finishConnect() || (throw new ConnectException(s"Connection to [$remoteAddress] failed"))
          log.debug("Connection established to [{}]", remoteAddress)
          completeConnect(registration, commander, options)
        } catch {
          case e: IOException ⇒
            log.debug("Could not establish connection to [{}] due to {}", remoteAddress, e)
            stop()
        }

      case ReceiveTimeout ⇒
        if (timeout.isDefined) context.setReceiveTimeout(Duration.Undefined) // Clear the timeout
        log.debug("Connect timeout expired, could not establish connection to [{}]", remoteAddress)
        stop()
    }
  }
}
