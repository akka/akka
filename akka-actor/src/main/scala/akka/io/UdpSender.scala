/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.nio.channels.DatagramChannel

import scala.collection.immutable
import scala.util.control.NonFatal
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.io.Inet.SocketOption
import akka.io.Udp._
import akka.actor._
import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 */
@silent
private[io] class UdpSender(
    val udp: UdpExt,
    channelRegistry: ChannelRegistry,
    commander: ActorRef,
    options: immutable.Traversable[SocketOption])
    extends Actor
    with ActorLogging
    with WithUdpSend
    with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket
    options.foreach { _.beforeDatagramBind(socket) }

    datagramChannel
  }
  channelRegistry.register(channel, initialOps = 0)

  def receive: Receive = {
    case registration: ChannelRegistration =>
      options.foreach {
        case v2: Inet.SocketOptionV2 => v2.afterConnect(channel.socket)
        case _                       =>
      }
      commander ! SimpleSenderReady
      context.become(sendHandlers(registration))
  }

  override def postStop(): Unit = if (channel.isOpen) {
    log.debug("Closing DatagramChannel after being stopped")
    try channel.close()
    catch {
      case NonFatal(e) => log.debug("Error closing DatagramChannel: {}", e)
    }
  }
}
