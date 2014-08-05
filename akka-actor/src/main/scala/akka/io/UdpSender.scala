/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.nio.channels.DatagramChannel
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.io.Inet.SocketOption
import akka.io.Udp._
import akka.actor._

/**
 * INTERNAL API
 */
private[io] class UdpSender(val udp: UdpExt,
                            channelRegistry: ChannelRegistry,
                            commander: ActorRef,
                            options: immutable.Traversable[SocketOption])
  extends Actor with ActorLogging with WithUdpSend with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    options foreach { _.beforeBind(datagramChannel) }

    datagramChannel
  }
  channelRegistry.register(channel, initialOps = 0)

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      options.foreach(_.afterConnect(channel))
      commander ! SimpleSenderReady
      context.become(sendHandlers(registration))
  }

  override def postStop(): Unit = if (channel.isOpen) {
    log.debug("Closing DatagramChannel after being stopped")
    try channel.close()
    catch {
      case NonFatal(e) ⇒ log.debug("Error closing DatagramChannel: {}", e)
    }
  }
}

