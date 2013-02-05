/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorLogging, Actor, ActorRef }
import akka.io.UdpFF._
import akka.io.Inet.SocketOption
import akka.io.SelectionHandler._
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.io.UdpFF.Received
import akka.io.SelectionHandler.RegisterChannel

private[io] class UdpFFListener(selectorRouter: ActorRef,
                                handler: ActorRef,
                                endpoint: InetSocketAddress,
                                bindCommander: ActorRef,
                                val udpFF: UdpFFExt,
                                options: immutable.Traversable[SocketOption])
  extends Actor with ActorLogging with WithUdpFFSend {
  import udpFF.settings._
  import udpFF.bufferPool

  def selector: ActorRef = context.parent

  context.watch(handler) // sign death pact
  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket
    options.foreach(_.beforeDatagramBind(socket))
    socket.bind(endpoint) // will blow up the actor constructor if the bind fails
    datagramChannel
  }
  context.parent ! RegisterChannel(channel, OP_READ)
  log.debug("Successfully bound to {}", endpoint)

  def receive: Receive = {
    case ChannelRegistered ⇒
      bindCommander ! Bound
      context.become(readHandlers orElse sendHandlers, discardOld = true)
  }

  def readHandlers: Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doReceive(handler)

    case Unbind ⇒
      log.debug("Unbinding endpoint {}", endpoint)
      channel.close()
      sender ! Unbound
      log.debug("Unbound endpoint {}, stopping listener", endpoint)
      context.stop(self)
  }

  def doReceive(handler: ActorRef): Unit = {
    val buffer = bufferPool.acquire()
    try {
      buffer.clear()
      buffer.limit(DirectBufferSize)

      channel.receive(buffer) match {
        case sender: InetSocketAddress ⇒
          buffer.flip()
          handler ! Received(ByteString(buffer), sender)
        case _ ⇒ // Ignore
      }

      selector ! ReadInterest
    } finally bufferPool.release(buffer)
  }

  override def postStop() {
    if (channel.isOpen) {
      log.debug("Closing DatagramChannel after being stopped")
      try channel.close()
      catch {
        case NonFatal(e) ⇒ log.error(e, "Error closing DatagramChannel")
      }
    }
  }
}
