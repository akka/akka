/*
 * Copyright (C) 2018-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetSocketAddress

import akka.AkkaException
import akka.actor.{ Actor, ActorLogging, ActorRef, Stash }
import akka.annotation.InternalApi
import akka.io.Tcp
import akka.io.dns.internal.DnsClient.Answer
import akka.util.ByteString
import akka.event.LoggingAdapter

import scala.collection.mutable

/**
 * INTERNAL API
 */
@InternalApi private[akka] class TcpDnsClient(tcp: ActorRef, ns: InetSocketAddress, answerRecipient: ActorRef)
    extends Actor
    with ActorLogging
    with Stash {
  import TcpDnsClient._

  override def receive: Receive = idle

  val idle: Receive = {
    case _: Message =>
      stash()
      log.debug("Connecting to [{}]", ns)
      tcp ! Tcp.Connect(ns)
      context.become(connecting)
  }

  val connecting: Receive = {
    case failure @ Tcp.CommandFailed(_: Tcp.Connect) =>
      throwFailure(s"Failed to connect to TCP DNS server at [$ns]", failure.cause)
    case _: Tcp.Connected =>
      log.debug("Connected to TCP address [{}]", ns)
      val connection = sender()
      writer = new Writing(connection, log)
      context.become(ready())
      connection ! Tcp.Register(self)
      unstashAll()
    case _: Message =>
      stash()
  }

  def ready(buffer: ByteString = ByteString.empty): Receive = {
    case msg: Message => writer = writer.maybeWriteMessage(msg)
    case Ack          => writer = writer.ack()

    case failure: Tcp.CommandFailed =>
      writer.connection ! Tcp.Abort
      throwFailure("TCP command failed", failure.cause)

    case Tcp.Received(newData) =>
      val data = buffer ++ newData
      // TCP DNS responses are prefixed by 2 bytes encoding the length of the response
      val prefixSize = 2
      if (data.length < prefixSize)
        context.become(ready(data))
      else {
        val expectedPayloadLength = decodeLength(data)
        if (data.drop(prefixSize).length < expectedPayloadLength)
          context.become(ready(data))
        else {
          answerRecipient ! parseResponse(data.drop(prefixSize))
          context.become(ready(ByteString.empty))
          if (data.length > prefixSize + expectedPayloadLength) {
            self ! Tcp.Received(data.drop(prefixSize + expectedPayloadLength))
          }
        }
      }
    case Tcp.PeerClosed =>
      context.become(idle)

    case Tcp.ErrorClosed(cause) =>
      throwFailure(s"Connection closed with error $cause", None)

  }

  private var writer: Writer = _

  private def parseResponse(data: ByteString) = {
    val msg = Message.parse(data)
    log.debug("Decoded TCP DNS response [{}]", msg)
    if (msg.flags.isTruncated) {
      log.warning("TCP DNS response truncated")
    }
    val (recs, additionalRecs) =
      if (msg.flags.responseCode == ResponseCode.SUCCESS) (msg.answerRecs, msg.additionalRecs) else (Nil, Nil)
    Answer(msg.id, recs, additionalRecs)
  }

  private def throwFailure(message: String, cause: Option[Throwable]): Nothing =
    TcpDnsClient.throwFailure(message, cause, log, answerRecipient)
}
private[internal] object TcpDnsClient {
  def encodeLength(length: Int): ByteString =
    ByteString((length / 256).toByte, length.toByte)

  def decodeLength(data: ByteString): Int =
    ((data(0).toInt + 256) % 256) * 256 + ((data(1) + 256) % 256)

  def throwFailure(message: String, cause: Option[Throwable], log: LoggingAdapter, reportTo: ActorRef): Nothing = {
    cause match {
      case None =>
        log.warning("TCP DNS client failed: {}", message)
        reportTo ! DnsClient.TcpDropped
        throw new AkkaException(message)

      case Some(throwable) =>
        log.warning(throwable, "TCP DNS client failed: {}", message)
        reportTo ! DnsClient.TcpDropped
        throw new AkkaException(message, throwable)
    }
  }

  abstract class Writer(val connection: ActorRef, val log: LoggingAdapter) {
    def maybeWriteMessage(msg: Message): Writer
    def ack(): Writer

    protected def writeMessage(msg: Message): Unit = {
      val bytes = msg.write()
      connection ! Tcp.Write(encodeLength(bytes.length) ++ bytes, Ack)
    }
  }

  class Writing(connection: ActorRef, log: LoggingAdapter) extends Writer(connection, log) {
    def maybeWriteMessage(msg: Message): Writer = {
      writeMessage(msg)

      new Buffering(connection, log)
    }

    def ack(): Writer = {
      log.warning("Unexpected Ack in TCP DNS client")
      this
    }
  }

  class Buffering(connection: ActorRef, log: LoggingAdapter) extends Writer(connection, log) {
    def maybeWriteMessage(msg: Message): Writer = {
      buffer.enqueue(msg)

      if (buffer.size < 11) {
        // do nothing, just the above enqueue
      } else if (toDrop < 1) {
        toDrop = buffer.size - 10
      } else {
        log.info("Dropping DNS request [{}] due to TCP backpressure", buffer.dequeue())
        toDrop -= 1
      }

      this
    }

    def ack(): Writer =
      if (buffer.isEmpty) new Writing(connection, log)
      else {
        writeMessage(buffer.dequeue())
        if (toDrop > 0) {
          toDrop -= 1
        }
        this
      }

    private val buffer = mutable.Queue.empty[Message]
    private var toDrop: Int = 0
  }

  case object Ack extends Tcp.Event
}
