/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.io.Inet.SocketOption
import akka.io.SelectionHandler._
import akka.io.UdpConn._
import akka.util.ByteString
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._
import scala.collection.immutable
import scala.util.control.NonFatal

private[io] class UdpConnection(selectorRouter: ActorRef,
                                handler: ActorRef,
                                localAddress: Option[InetSocketAddress],
                                remoteAddress: InetSocketAddress,
                                bindCommander: ActorRef,
                                val udpConn: UdpConnExt,
                                options: immutable.Traversable[SocketOption]) extends Actor with ActorLogging {

  def selector: ActorRef = context.parent

  import udpConn._
  import udpConn.settings._

  var pendingSend: (Send, ActorRef) = null
  def writePending = pendingSend ne null

  context.watch(handler) // sign death pact
  val channel = {
    val datagramChannel = DatagramChannel.open
    datagramChannel.configureBlocking(false)
    val socket = datagramChannel.socket
    options.foreach(_.beforeDatagramBind(socket))
    localAddress foreach { socket.bind } // will blow up the actor constructor if the bind fails
    datagramChannel.connect(remoteAddress)
    datagramChannel
  }
  selector ! RegisterChannel(channel, OP_READ)
  log.debug("Successfully connected to {}", remoteAddress)

  def receive = {
    case ChannelRegistered ⇒
      bindCommander ! Connected
      context.become(connected, discardOld = true)
  }

  def connected: Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler)

    case Close ⇒
      log.debug("Closing UDP connection to {}", remoteAddress)
      channel.close()
      sender ! Disconnected
      log.debug("Connection closed to {}, stopping listener", remoteAddress)
      context.stop(self)

    case send: Send if writePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! CommandFailed(send)

    case send: Send if send.payload.isEmpty ⇒
      if (send.wantsAck)
        sender ! send.ack

    case send: Send ⇒
      pendingSend = (send, sender)
      selector ! WriteInterest

    case ChannelWritable ⇒ doWrite()
  }

  def doRead(handler: ActorRef): Unit = {
    val buffer = bufferPool.acquire()
    try {
      buffer.clear()
      buffer.limit(DirectBufferSize)

      if (channel.read(buffer) > 0) handler ! Received(ByteString(buffer))

    } finally {
      selector ! ReadInterest
      bufferPool.release(buffer)
    }
  }

  final def doWrite(): Unit = {

    val buffer = udpConn.bufferPool.acquire()
    try {
      val (send, commander) = pendingSend
      buffer.clear()
      send.payload.copyToBuffer(buffer)
      buffer.flip()
      val writtenBytes = channel.write(buffer)
      if (TraceLogging) log.debug("Wrote {} bytes to channel", writtenBytes)

      // Datagram channel either sends the whole message, or nothing
      if (writtenBytes == 0) commander ! CommandFailed(send)
      else if (send.wantsAck) commander ! send.ack

    } finally {
      udpConn.bufferPool.release(buffer)
      pendingSend = null
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
