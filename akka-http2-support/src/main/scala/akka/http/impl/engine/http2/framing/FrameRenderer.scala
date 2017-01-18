/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2
package framing

import java.nio.ByteOrder

import akka.util.ByteString.ByteString1C
import akka.util.{ ByteString, ByteStringBuilder }

import Http2Protocol.FrameType

import scala.annotation.tailrec

/** INTERNAL API */
private[http2] object FrameRenderer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  def render(frame: FrameEvent): ByteString =
    frame match {
      case GoAwayFrame(lastStreamId, errorCode, debug) ⇒
        val bb = new ByteStringBuilder
        bb.putInt(lastStreamId)
        bb.putInt(errorCode.id)
        // appends debug data, if any
        bb.append(debug)

        renderFrame(
          Http2Protocol.FrameType.GOAWAY,
          Http2Protocol.Flags.NO_FLAGS,
          Http2Protocol.NoStreamId,
          bb.result
        )

      case DataFrame(streamId, endStream, payload) ⇒
        // TODO: should padding be emitted? In which cases?

        renderFrame(
          Http2Protocol.FrameType.DATA,
          Http2Protocol.Flags.END_STREAM.ifSet(endStream),
          streamId,
          payload
        )
      case HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment, prioInfo) ⇒
        val renderedPrioInfo = prioInfo.map(renderPriorityInfo).getOrElse(ByteString.empty)

        renderFrame(
          Http2Protocol.FrameType.HEADERS,
          Http2Protocol.Flags.END_STREAM.ifSet(endStream) |
            Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders) |
            Http2Protocol.Flags.PRIORITY.ifSet(prioInfo.isDefined),
          streamId,
          renderedPrioInfo ++ headerBlockFragment
        )

      case WindowUpdateFrame(streamId, windowSizeIncrement) ⇒
        val bb = new ByteStringBuilder
        bb.putInt(windowSizeIncrement)

        renderFrame(
          Http2Protocol.FrameType.WINDOW_UPDATE,
          Http2Protocol.Flags.NO_FLAGS,
          streamId,
          bb.result()
        )

      case ContinuationFrame(streamId, endHeaders, payload) ⇒
        renderFrame(
          Http2Protocol.FrameType.CONTINUATION,
          Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
          streamId,
          payload)

      case SettingsFrame(settings) ⇒
        val bb = new ByteStringBuilder
        @tailrec def renderNext(remaining: Seq[Setting]): Unit =
          remaining match {
            case Setting(id, value) +: remaining ⇒
              bb.putShort(id.id)
              bb.putInt(value)

              renderNext(remaining)
            case Nil ⇒
          }

        renderNext(settings)

        renderFrame(
          Http2Protocol.FrameType.SETTINGS,
          Http2Protocol.Flags.NO_FLAGS,
          Http2Protocol.NoStreamId,
          bb.result()
        )

      case _: SettingsAckFrame ⇒
        renderFrame(
          Http2Protocol.FrameType.SETTINGS,
          Http2Protocol.Flags.ACK,
          Http2Protocol.NoStreamId,
          ByteString.empty
        )

      case PingFrame(ack, data) ⇒
        renderFrame(
          Http2Protocol.FrameType.PING,
          Http2Protocol.Flags.ACK.ifSet(ack),
          Http2Protocol.NoStreamId,
          data
        )

      case RstStreamFrame(streamId, errorCode) ⇒
        renderFrame(
          Http2Protocol.FrameType.RST_STREAM,
          Http2Protocol.Flags.NO_FLAGS,
          streamId,
          new ByteStringBuilder().putInt(errorCode.id).result
        )

      case PushPromiseFrame(streamId, endHeaders, promisedStreamId, headerBlockFragment) ⇒
        renderFrame(
          Http2Protocol.FrameType.PUSH_PROMISE,
          Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
          streamId,
          new ByteStringBuilder()
            .putInt(promisedStreamId)
            .append(headerBlockFragment)
            .result()
        )

      case frame @ PriorityFrame(streamId, exclusiveFlag, streamDependency, weight) ⇒
        renderFrame(
          Http2Protocol.FrameType.PRIORITY,
          Http2Protocol.Flags.NO_FLAGS,
          streamId,
          renderPriorityInfo(frame)
        )
    }

  def renderFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): ByteString = {
    val length = payload.length
    val headerBytes = new Array[Byte](9)
    headerBytes(0) = (length >> 16).toByte
    headerBytes(1) = (length >> 8).toByte
    headerBytes(2) = (length >> 0).toByte
    headerBytes(3) = tpe.id.toByte
    headerBytes(4) = flags.value.toByte
    headerBytes(5) = (streamId >> 24).toByte
    headerBytes(6) = (streamId >> 16).toByte
    headerBytes(7) = (streamId >> 8).toByte
    headerBytes(8) = (streamId >> 0).toByte

    ByteString1C(headerBytes) ++ payload
  }
  def renderPriorityInfo(priorityFrame: PriorityFrame): ByteString = {
    val exclusiveBit: Int = if (priorityFrame.exclusiveFlag) 0x80000000 else 0
    new ByteStringBuilder().putInt(exclusiveBit | priorityFrame.streamDependency).putByte(priorityFrame.weight.toByte).result
  }
}
