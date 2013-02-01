/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor._
import java.nio.channels.DatagramChannel
import akka.io.UdpFF._
import akka.io.SelectionHandler.RegisterChannel

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 */
private[io] class UdpFFSender(val udpFF: UdpFFExt, val selector: ActorRef)
  extends Actor with ActorLogging with WithUdpFFBufferPool with WithUdpFFSend {

  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    datagramChannel
  }
  selector ! RegisterChannel(channel, 0)

  def receive: Receive = internalReceive orElse sendHandlers

  def internalReceive: Receive = {
    case SimpleSender ⇒ sender ! SimpleSendReady
  }

  override def postStop(): Unit = if (channel.isOpen) channel.close()

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

}

