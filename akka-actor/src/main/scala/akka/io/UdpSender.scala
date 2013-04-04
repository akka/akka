/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor._
import java.nio.channels.DatagramChannel
import akka.io.Udp._
import akka.io.SelectionHandler.{ ChannelRegistered, RegisterChannel }
import scala.collection.immutable
import akka.io.Inet.SocketOption
import scala.util.control.NonFatal

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 *
 * INTERNAL API
 */
private[io] class UdpSender(val udp: UdpExt, options: immutable.Traversable[SocketOption], val commander: ActorRef)
  extends Actor with ActorLogging with WithUdpSend {

  def selector: ActorRef = context.parent

  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket

    options foreach { _.beforeDatagramBind(socket) }

    datagramChannel
  }
  selector ! RegisterChannel(channel, 0)

  def receive: Receive = {
    case ChannelRegistered ⇒
      context.become(sendHandlers, discardOld = true)
      commander ! SimpleSendReady
  }

  override def postStop(): Unit = if (channel.isOpen) {
    log.debug("Closing DatagramChannel after being stopped")
    try channel.close()
    catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing DatagramChannel")
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

}

