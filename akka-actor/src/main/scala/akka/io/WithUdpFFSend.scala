/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.io.UdpFF.{ CommandFailed, Send }
import akka.io.SelectionHandler._
import java.nio.channels.DatagramChannel

/**
 * INTERNAL API
 */
private[io] trait WithUdpFFSend {
  me: Actor with ActorLogging ⇒

  var pendingSend: Send = null
  var pendingCommander: ActorRef = null
  // If send fails first, we allow a second go after selected writable, but no more. This flag signals that
  // pending send was already tried once.
  var retriedSend = false
  def hasWritePending = pendingSend ne null

  def selector: ActorRef
  def channel: DatagramChannel
  def udpFF: UdpFFExt
  val settings = udpFF.settings

  import settings._

  def sendHandlers: Receive = {

    case send: Send if hasWritePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! CommandFailed(send)

    case send: Send if send.payload.isEmpty ⇒
      if (send.wantsAck)
        sender ! send.ack

    case send: Send ⇒
      pendingSend = send
      pendingCommander = sender
      doSend()

    case ChannelWritable ⇒ if (hasWritePending) doSend()

  }

  final def doSend(): Unit = {

    val buffer = udpFF.bufferPool.acquire()
    try {
      buffer.clear()
      pendingSend.payload.copyToBuffer(buffer)
      buffer.flip()
      val writtenBytes = channel.send(buffer, pendingSend.target)
      if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

      // Datagram channel either sends the whole message, or nothing
      if (writtenBytes == 0) {
        if (retriedSend) {
          pendingCommander ! CommandFailed(pendingSend)
          retriedSend = false
          pendingSend = null
          pendingCommander = null
        } else {
          selector ! WriteInterest
          retriedSend = true
        }
      } else {
        if (pendingSend.wantsAck) pendingCommander ! pendingSend.ack
        retriedSend = false
        pendingSend = null
        pendingCommander = null
      }

    } finally {
      udpFF.bufferPool.release(buffer)
    }

  }
}
