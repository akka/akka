/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

/** INTERNAL API */
private[akka] object Http2Compliance {

  final class IllegalHttp2StreamIdException(id: Int, expected: String)
    extends IllegalArgumentException(s"Illegal HTTP/2 stream id: [$id]. $expected!")

  final class MissingHttpIdHeaderException extends IllegalArgumentException("Expected `Http2StreamIdHeader` header to be present but was missing!")

  final def missingHttpIdHeaderException = throw new MissingHttpIdHeaderException

  // require methods use `if` because `require` allocates

  final def requireZeroStreamId(id: Int): Unit =
    if (id != 0) throw new IllegalHttp2StreamIdException(id, "MUST BE == 0.")

  /** checks if the stream id was client initiated, by checking if the stream id was odd-numbered */
  final def isClientInitiatedStreamId(id: Int): Boolean = id % 2 != 0

  final class IllegalHttp2FrameSize(size: Int, expected: String)
    extends IllegalArgumentException(s"Illegal HTTP/2 frame size: [$size]. $expected!")

  final def requireFrameSize(size: Int, max: Int): Unit =
    if (size != max) throw new IllegalHttp2FrameSize(size, s"MUST BE == $max.")

}
