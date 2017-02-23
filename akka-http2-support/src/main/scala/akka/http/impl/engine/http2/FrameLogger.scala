/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.annotation.InternalApi
import akka.stream.scaladsl.{ BidiFlow, Flow }
import akka.util.ByteString

import scala.collection.immutable.Seq

/**
 * INTERNAL API
 */
@InternalApi
private[http2] object FrameLogger {
  final val maxBytes = 16

  def bidi: BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, NotUsed] =
    BidiFlow.fromFlows(
      Flow[FrameEvent].log(s"${Console.RED}DOWN${Console.RESET}", FrameLogger.logEvent),
      Flow[FrameEvent].log(s"${Console.GREEN} UP ${Console.RESET}", FrameLogger.logEvent))

  def logEvent(frameEvent: FrameEvent): String = {
    case class LogEntry(
      streamId:       Int,
      shortFrameType: String,
      extraInfo:      String,
      flags:          Option[String]*
    )

    def flag(value: Boolean, name: String): Option[String] = if (value) Some(name) else None
    def hex(bytes: ByteString): String = {
      val num = math.min(maxBytes, bytes.size)

      val ellipsis =
        if (num < bytes.size) s" [... ${bytes.size - num} more bytes]"
        else ""

      bytes
        .take(num)
        .map(_ formatted "%02x")
        .mkString(" ") + ellipsis
    }

    def entryForFrame(frameEvent: FrameEvent): LogEntry =
      frameEvent match {
        case PingFrame(false, data) ⇒ LogEntry(0, "PING", hex(data))
        case PingFrame(true, data)  ⇒ LogEntry(0, "PONG", hex(data))
        case HeadersFrame(streamId, endStream, endHeaders, payload, prio) ⇒
          val prioInfo = if (prio.isDefined) display(entryForFrame(prio.get)) + " " else ""

          LogEntry(streamId, "HEAD", prioInfo + hex(payload), flag(endStream, "ES"), flag(endHeaders, "EH"))
        case ContinuationFrame(streamId, endHeaders, payload) ⇒
          LogEntry(streamId, "CONT", hex(payload), flag(endHeaders, "EH"))
        case DataFrame(streamId, endStream, payload) ⇒
          LogEntry(streamId, "DATA", hex(payload), flag(endStream, "ES"))

        case GoAwayFrame(lastStreamId, errorCode, debug) ⇒
          LogEntry(0, "GOAY", s"lastStreamId = $lastStreamId, errorCode = $errorCode, debug = ${debug.utf8String}")

        case ParsedHeadersFrame(streamId, endStream, kvPairs, prio) ⇒
          val prioInfo = if (prio.isDefined) display(entryForFrame(prio.get)) + " " else ""
          val kvInfo = kvPairs.map {
            case (key, value) ⇒ s"$key -> $value"
          }.mkString(", ")
          LogEntry(streamId, "HEAD", prioInfo + kvInfo, flag(endStream, "ES"))

        case PriorityFrame(streamId, exclusive, streamDependency, weight) ⇒
          LogEntry(streamId, "PRIO", s"streamDependency = $streamDependency, weight: $weight", flag(exclusive, "EX"))

        case RstStreamFrame(streamId, errorCode) ⇒
          LogEntry(streamId, "RSET", errorCode.toString)

        case SettingsFrame(settings) ⇒
          val settingsInfo = settings.map {
            case Setting(id, value) ⇒ s"$id -> $value"
          }.mkString(", ")
          LogEntry(0, "SETT", settingsInfo)

        case SettingsAckFrame(s) ⇒
          val acksInfo = formatSettings(s)
          LogEntry(0, "SETA", acksInfo)

        case WindowUpdateFrame(streamId, windowSizeIncrement) ⇒
          LogEntry(streamId, "WIND", s"+ $windowSizeIncrement")

        case other: StreamFrameEvent ⇒
          LogEntry(other.streamId, "UNKN", other.toString)
        case other ⇒
          LogEntry(0, "UNKN", other.toString)
      }

    def display(entry: LogEntry): String = {
      import entry._

      import Console._
      f"$GREEN$streamId%4d $YELLOW$shortFrameType%s $RED${flags.flatMap(x ⇒ x).mkString(" ")} $RESET$extraInfo"
    }
    display(entryForFrame(frameEvent))
  }

  private def formatSettings(s: Seq[Setting]) =
    s.map {
      case Setting(id, value) ⇒ s"$id -> $value"
    }.mkString(", ")

}
