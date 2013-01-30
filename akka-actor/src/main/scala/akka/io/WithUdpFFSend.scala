/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.io.UdpFF.{ CommandFailed, Send }
import akka.io.UdpFFSelector._
import java.nio.channels.DatagramChannel

trait WithUdpFFSend {
  me: Actor with ActorLogging with WithUdpFFBufferPool ⇒

  var pendingSend: (Send, ActorRef) = null
  def writePending = pendingSend ne null

  def selector: ActorRef
  def channel: DatagramChannel
  def udpFF: UdpFFExt
  val settings = udpFF.Settings

  import settings._

  def sendHandlers: Receive = {

    case send: Send if writePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! CommandFailed(send)

    case send: Send if send.payload.isEmpty ⇒
      if (send.wantsAck)
        sender ! send.ack

    case send: Send ⇒
      pendingSend = (send, sender)
      selector ! WriteInterest

    case ChannelWritable ⇒ doSend()

  }

  final def doSend(): Unit = {

    val buffer = acquireBuffer()
    try {
      val (send, commander) = pendingSend
      buffer.clear()
      send.payload.copyToBuffer(buffer)
      buffer.flip()
      val writtenBytes = channel.send(buffer, send.target)
      if (TraceLogging) log.debug("Wrote {} bytes to channel", writtenBytes)

      // Datagram channel either sends the whole message, or nothing
      if (writtenBytes == 0) commander ! CommandFailed(send)
      else if (send.wantsAck) commander ! send.ack

    } finally {
      releaseBuffer(buffer)
      pendingSend = null
    }

  }
}
