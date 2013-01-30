/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorLogging, Actor, ActorRef }
import akka.io.UdpFF._
import akka.io.UdpFFSelector._
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._
import scala.collection.immutable
import scala.util.control.NonFatal

private[io] class UdpFFListener(selectorRouter: ActorRef,
                                handler: ActorRef,
                                endpoint: InetSocketAddress,
                                bindCommander: ActorRef,
                                val udpFF: UdpFFExt,
                                options: immutable.Traversable[SocketOption])
  extends Actor with ActorLogging with WithUdpFFBufferPool with WithUdpFFSend {
  import udpFF.Settings._

  def selector: ActorRef = context.parent

  context.watch(handler) // sign death pact
  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket
    options.foreach(_.beforeBind(socket))
    socket.bind(endpoint) // will blow up the actor constructor if the bind fails
    datagramChannel
  }
  context.parent ! RegisterDatagramChannel(channel, OP_READ)
  bindCommander ! Bound
  log.debug("Successfully bound to {}", endpoint)

  def receive: Receive = receiveInternal orElse sendHandlers

  def receiveInternal: Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doReceive(handler, None)

    case CommandFailed(RegisterDatagramChannel(datagramChannel, _)) ⇒
      log.warning("Could not bind to UDP port since selector capacity limit is reached, aborting bind")
      try datagramChannel.close()
      catch {
        case NonFatal(e) ⇒ log.error(e, "Error closing channel")
      }

    case Unbind ⇒
      log.debug("Unbinding endpoint {}", endpoint)
      channel.close()
      sender ! Unbound
      log.debug("Unbound endpoint {}, stopping listener", endpoint)
      context.stop(self)
  }

  def doReceive(handler: ActorRef, closeCommander: Option[ActorRef]): Unit = {
    val buffer = acquireBuffer()
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
    } finally releaseBuffer(buffer)
  }

  override def postStop() {
    try {
      if (channel.isOpen) {
        log.debug("Closing serverSocketChannel after being stopped")
        channel.close()
      }
    } catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing ServerSocketChannel")
    }
  }
}
