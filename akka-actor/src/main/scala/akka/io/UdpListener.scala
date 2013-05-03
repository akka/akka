/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorLogging, Actor, ActorRef }
import akka.io.SelectionHandler._
import akka.io.Udp._
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[io] class UdpListener(val udp: UdpExt,
                              val bindCommander: ActorRef,
                              val bind: Bind)
  extends Actor with ActorLogging with WithUdpSend {

  import udp.bufferPool
  import udp.settings._

  def selector: ActorRef = context.parent

  context.watch(bind.handler) // sign death pact

  val channel = DatagramChannel.open
  channel.configureBlocking(false)

  val localAddress =
    try {
      val socket = channel.socket
      bind.options.foreach(_.beforeDatagramBind(socket))
      socket.bind(bind.localAddress)
      val ret = socket.getLocalSocketAddress match {
        case isa: InetSocketAddress ⇒ isa
        case x                      ⇒ throw new IllegalArgumentException(s"bound to unknown SocketAddress [$x]")
      }
      context.parent ! RegisterChannel(channel, OP_READ)
      log.debug("Successfully bound to [{}]", ret)
      ret
    } catch {
      case NonFatal(e) ⇒
        bindCommander ! CommandFailed(bind)
        log.error(e, "Failed to bind UDP channel to endpoint [{}]", bind.localAddress)
        context.stop(self)
    }

  def receive: Receive = {
    case ChannelRegistered ⇒
      bindCommander ! Bound(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
      context.become(readHandlers orElse sendHandlers, discardOld = true)
  }

  def readHandlers: Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doReceive(bind.handler)

    case Unbind ⇒
      log.debug("Unbinding endpoint [{}]", bind.localAddress)
      try {
        channel.close()
        sender ! Unbound
        log.debug("Unbound endpoint [{}], stopping listener", bind.localAddress)
      } finally context.stop(self)
  }

  def doReceive(handler: ActorRef): Unit = {
    @tailrec def innerReceive(readsLeft: Int, buffer: ByteBuffer) {
      buffer.clear()
      buffer.limit(DirectBufferSize)

      channel.receive(buffer) match {
        case sender: InetSocketAddress ⇒
          buffer.flip()
          handler ! Received(ByteString(buffer), sender)
          if (readsLeft > 0) innerReceive(readsLeft - 1, buffer)
        case null ⇒ // null means no data was available
      }
    }

    val buffer = bufferPool.acquire()
    try innerReceive(BatchReceiveLimit, buffer) finally {
      bufferPool.release(buffer)
      selector ! ReadInterest
    }
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
