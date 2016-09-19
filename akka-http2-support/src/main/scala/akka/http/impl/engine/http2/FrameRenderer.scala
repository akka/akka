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
      case DataFrame(flags, streamId, payload) ⇒
        // TODO: should padding be emitted? In which cases?

        renderFrame(
          Http2Protocol.FrameType.DATA,
          flags,
          streamId,
          payload
        )
      case HeadersFrame(flags, streamId, headerBlockFragment) ⇒
        // TODO: will we ever emit priority stuff? will need other representation otherwise

        renderFrame(
          Http2Protocol.FrameType.HEADERS,
          flags,
          streamId,
          headerBlockFragment
        )
    }

  def renderFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): ByteString = {
    val length = payload.length
    val headerBytes = new Array[Byte](9)
    headerBytes(0) = (length >> 16).toByte
    headerBytes(1) = (length >> 8).toByte
    headerBytes(2) = (length >> 0).toByte
    headerBytes(3) = tpe.id.toByte
    headerBytes(4) = flags.value
    headerBytes(5) = (streamId >> 24).toByte
    headerBytes(6) = (streamId >> 16).toByte
    headerBytes(7) = (streamId >> 8).toByte
    headerBytes(8) = (streamId >> 0).toByte

    ByteString1C(headerBytes) ++ payload
  }
}
