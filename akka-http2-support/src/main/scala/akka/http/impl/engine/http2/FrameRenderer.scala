/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.nio.ByteOrder

import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.util.ByteString
import akka.util.ByteString.ByteString1C
import akka.util.ByteStringBuilder

import scala.annotation.tailrec

object FrameRenderer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  def render(frame: FrameEvent): ByteString =
    frame match {
      case DataFrame(streamId, endStream, payload) ⇒
        // TODO: should padding be emitted? In which cases?

        renderFrame(
          Http2Protocol.FrameType.DATA,
          Http2Protocol.Flags.END_STREAM.ifSet(endStream),
          streamId,
          payload
        )
      case HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment) ⇒
        // TODO: will we ever emit priority stuff? will need other representation otherwise

        renderFrame(
          Http2Protocol.FrameType.HEADERS,
          Http2Protocol.Flags.END_STREAM.ifSet(endStream) |
            Http2Protocol.Flags.END_HEADERS.ifSet(endHeaders),
          streamId,
          headerBlockFragment
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

      case SettingsAckFrame ⇒
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
}
