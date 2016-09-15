/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.util.ByteString
import akka.util.ByteString.ByteString1C

object FrameRenderer {
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

      case SettingsFrame(settings) ⇒
        // FIXME
        renderFrame(
          Http2Protocol.FrameType.SETTINGS,
          0,
          0,
          ByteString.empty
        )

      case SettingsAckFrame ⇒
        // FIXME
        renderFrame(
          Http2Protocol.FrameType.SETTINGS,
          Http2Protocol.Flags.ACK.value,
          0,
          ByteString.empty
        )
    }

  def renderFrame(tpe: FrameType, flags: Int, streamId: Int, payload: ByteString): ByteString = {
    val length = payload.length
    val headerBytes = new Array[Byte](9)
    headerBytes(0) = (length >> 16).toByte
    headerBytes(1) = (length >> 8).toByte
    headerBytes(2) = (length >> 0).toByte
    headerBytes(3) = tpe.id.toByte
    headerBytes(4) = flags.toByte
    headerBytes(5) = (streamId >> 24).toByte
    headerBytes(6) = (streamId >> 16).toByte
    headerBytes(7) = (streamId >> 8).toByte
    headerBytes(8) = (streamId >> 0).toByte

    ByteString1C(headerBytes) ++ payload
  }
}
