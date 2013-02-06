/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorLogging, Actor, ActorRef }
import akka.io.SelectionHandler._
import akka.io.UdpFF._
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[io] class UdpFFListener(val udpFF: UdpFFExt,
                                val bindCommander: ActorRef,
                                val bind: Bind)
  extends Actor with ActorLogging with WithUdpFFSend {

  import bind._
  import udpFF.bufferPool
  import udpFF.settings._

  def selector: ActorRef = context.parent

  context.watch(handler) // sign death pact
  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket
    options.foreach(_.beforeDatagramBind(socket))
    try socket.bind(endpoint)
    catch {
      case NonFatal(e) ⇒
        bindCommander ! CommandFailed(bind)
        log.error(e, "Failed to bind UDP channel")
        context.stop(self)
    }
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
