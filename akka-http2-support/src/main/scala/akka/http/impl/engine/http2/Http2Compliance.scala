/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.ErrorCode

/** INTERNAL API */
private[akka] object Http2Compliance {

  final class IllegalHttp2StreamIdException(id: Int, expected: String)
    extends IllegalArgumentException(s"Illegal HTTP/2 stream id: [$id]. $expected!")

  final class MissingHttpIdHeaderException extends IllegalArgumentException("Expected `Http2StreamIdHeader` header to be present but was missing!")

  final class IllegalHttp2StreamDependency(id: Int)
    extends IllegalArgumentException(s"Illegal self dependency of stream for id: [$id]!")

  final class IllegalPayloadInSettingsAckFrame(size: Int, expected: String) extends IllegalHttp2FrameSize(size, expected)

  final class IllegalPayloadLengthInSettingsFrame(size: Int, expected: String) extends IllegalHttp2FrameSize(size, expected)

  final def missingHttpIdHeaderException = throw new MissingHttpIdHeaderException

  // @DoNotInherit
  private[akka] sealed class IllegalHttp2FrameSize(size: Int, expected: String)
    extends Http2ProtocolException(ErrorCode.FRAME_SIZE_ERROR, s"Illegal HTTP/2 frame size: [$size]. $expected!")

  // require methods use `if` because `require` allocates

  /** Validate value of MAX_FRAME_SIZE setting. */
  def validateMaxFrameSize(value: Int): Unit = {
    import Http2Protocol.MinFrameSize
    import Http2Protocol.MaxFrameSize
    if (value < MinFrameSize) throw new Http2ProtocolException(ErrorCode.PROTOCOL_ERROR, s"MAX_FRAME_SIZE MUST NOT be < than $MinFrameSize, attempted setting to: $value!")
    if (value > MaxFrameSize) throw new Http2ProtocolException(ErrorCode.PROTOCOL_ERROR, s"MAX_FRAME_SIZE MUST NOT be > than $MaxFrameSize, attempted setting to: $value!")
  }

  class Http2ProtocolException(val errorCode: ErrorCode, message: String) extends IllegalStateException(message)
  class Http2ProtocolStreamException(val streamId: Int, val errorCode: ErrorCode, message: String) extends IllegalStateException(message)

  final def requireZeroStreamId(id: Int): Unit =
    if (id != 0) throw new IllegalHttp2StreamIdException(id, "MUST BE == 0.")

  final def requirePositiveWindowUpdateIncrement(streamId: Int, increment: Int): Unit =
    if (increment <= 0)
      if (streamId == 0) throw new Http2ProtocolException(ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE MUST be > 0, was: " + increment) // cause GOAWAY
      else throw new Http2ProtocolStreamException(streamId, ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE MUST be > 0, was: " + increment) // cause RST_STREAM

  /** checks if the stream id was client initiated, by checking if the stream id was odd-numbered */
  final def isClientInitiatedStreamId(id: Int): Boolean = id % 2 != 0

  final def requireFrameSize(size: Int, max: Int): Unit =
    if (size != max) throw new IllegalHttp2FrameSize(size, s"MUST BE == $max.")

  final def requireNoSelfDependency(id: Int, dependency: Int): Unit =
    if (id == dependency) throw new IllegalHttp2StreamDependency(id)
}
