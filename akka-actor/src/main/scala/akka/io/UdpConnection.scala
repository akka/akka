/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey._

import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.util.{ unused, ByteString }
import akka.io.SelectionHandler._
import akka.io.UdpConnected._

/**
 * INTERNAL API
 */
private[io] class UdpConnection(
    udpConn: UdpConnectedExt,
    channelRegistry: ChannelRegistry,
    commander: ActorRef,
    connect: Connect)
    extends Actor
    with ActorLogging
    with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import connect._
  import udpConn._
  import udpConn.settings._

  var pendingSend: (Send, ActorRef) = null
  def writePending = pendingSend ne null

  context.watch(handler) // sign death pact
  var channel: DatagramChannel = null

  if (remoteAddress.isUnresolved) {
    Dns.resolve(remoteAddress.getHostName)(context.system, self) match {
      case Some(r) =>
        doConnect(new InetSocketAddress(r.addr, remoteAddress.getPort))
      case None =>
        context.become(resolving(), discardOld = true)
    }
  } else {
    doConnect(remoteAddress)
  }

  def resolving(): Receive = {
    case r: Dns.Resolved =>
      reportConnectFailure {
        doConnect(new InetSocketAddress(r.addr, remoteAddress.getPort))
      }
  }

  def doConnect(@unused address: InetSocketAddress): Unit = {
    reportConnectFailure {
      channel = DatagramChannel.open
      channel.configureBlocking(false)
      val socket = channel.socket
      options.foreach(_.beforeDatagramBind(socket))
      localAddress.foreach(socket.bind)
      channel.connect(remoteAddress)
      channelRegistry.register(channel, OP_READ)
    }
    log.debug("Successfully connected to [{}]", remoteAddress)
  }

  def receive = {
    case registration: ChannelRegistration =>
      options.foreach {
        case v2: Inet.SocketOptionV2 => v2.afterConnect(channel.socket)
        case _                       =>
      }
      commander ! Connected
      context.become(connected(registration), discardOld = true)
  }

  def connected(registration: ChannelRegistration): Receive = {
    case SuspendReading  => registration.disableInterest(OP_READ)
    case ResumeReading   => registration.enableInterest(OP_READ)
    case ChannelReadable => doRead(registration, handler)

    case Disconnect =>
      log.debug("Closing UDP connection to [{}]", remoteAddress)
      channel.close()
      sender() ! Disconnected
      log.debug("Connection closed to [{}], stopping listener", remoteAddress)
      context.stop(self)

    case send: Send if writePending =>
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender() ! CommandFailed(send)

    case send: Send if send.payload.isEmpty =>
      if (send.wantsAck)
        sender() ! send.ack

    case send: Send =>
      pendingSend = (send, sender())
      registration.enableInterest(OP_WRITE)

    case ChannelWritable => doWrite()
  }

  def doRead(registration: ChannelRegistration, handler: ActorRef): Unit = {
    @tailrec def innerRead(readsLeft: Int, buffer: ByteBuffer): Unit = {
      buffer.clear()
      buffer.limit(DirectBufferSize)

      if (channel.read(buffer) > 0) {
        buffer.flip()
        handler ! Received(ByteString(buffer))
        innerRead(readsLeft - 1, buffer)
      }
    }
    val buffer = bufferPool.acquire()
    try innerRead(BatchReceiveLimit, buffer)
    finally {
      registration.enableInterest(OP_READ)
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
      if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

      // Datagram channel either sends the whole message, or nothing
      if (writtenBytes == 0) commander ! CommandFailed(send)
      else if (send.wantsAck) commander ! send.ack
    } finally {
      udpConn.bufferPool.release(buffer)
      pendingSend = null
    }
  }

  override def postStop(): Unit =
    if (channel.isOpen) {
      log.debug("Closing DatagramChannel after being stopped")
      try channel.close()
      catch {
        case NonFatal(e) => log.debug("Error closing DatagramChannel: {}", e)
      }
    }

  private def reportConnectFailure(thunk: => Unit): Unit = {
    try {
      thunk
    } catch {
      case NonFatal(e) =>
        log.debug(
          "Failure while connecting UDP channel to remote address [{}] local address [{}]: {}",
          remoteAddress,
          localAddress.getOrElse("undefined"),
          e)
        commander ! CommandFailed(connect)
        context.stop(self)
    }
  }
}
