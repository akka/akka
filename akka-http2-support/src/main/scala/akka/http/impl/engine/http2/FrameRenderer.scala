/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Protocol.FrameType
import akka.util.ByteString

object FrameRenderer {
  def render(frame: FrameEvent): ByteString =
    frame match {
      case DataFrame(streamId, endStream, payload) ⇒
        // TODO: should padding be emitted? In which cases?

        renderFrame(
          Protocol.FrameType.DATA,
          flag(endStream, Protocol.Flags.END_STREAM),
          streamId,
          payload
        )
      case HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment) ⇒
        // TODO: will we ever emit priority stuff? will need other representation otherwise

        renderFrame(
          Protocol.FrameType.HEADERS,
          flag(endStream, Protocol.Flags.END_STREAM) | flag(endHeaders, Protocol.Flags.END_HEADERS),
          streamId,
          headerBlockFragment
        )
    }

  def flag(bit: Boolean, flag: Int): Int =
    if (bit) flag
    else 0

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

    // TODO: prevent copying
    ByteString(headerBytes) ++ payload
  }
}
