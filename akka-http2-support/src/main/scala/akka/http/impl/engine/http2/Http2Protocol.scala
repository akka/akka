/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.util.ByteString

/**
 * INTERNAL API
 *
 * Defines constants as defined in the HTTP/2 specification.
 *
 * https://tools.ietf.org/html/rfc7540
 */
object Http2Protocol {
  // constants defined in the spec

  /**
   * The initial window size for both new streams and the overall connection
   * as defined by the specification.
   *
   * See https://tools.ietf.org/html/rfc7540#section-5.2.1:
   *    4.  The initial value for the flow-control window is 65,535 octets
   *        for both new streams and the overall connection.
   */
  final val InitialWindowSize = 65535

  /**
   * The stream id to be used for frames not associated with any individual stream
   * as defined by the specification.
   *
   * See https://tools.ietf.org/html/rfc7540#section-4.1:
   *
   *   Stream Identifier:  A stream identifier (see Section 5.1.1) expressed
   *   as an unsigned 31-bit integer.  The value 0x0 is reserved for
   *   frames that are associated with the connection as a whole as
   *   opposed to an individual stream.
   */
  final val NoStreamId = 0

  sealed abstract class FrameType(val id: Int) extends Product
  object FrameType {
    case object DATA extends FrameType(0x0)
    case object HEADERS extends FrameType(0x1)
    case object PRIORITY extends FrameType(0x2)
    case object RST_STREAM extends FrameType(0x3)
    case object SETTINGS extends FrameType(0x4)
    case object PUSH_PROMISE extends FrameType(0x5)
    case object PING extends FrameType(0x6)
    case object GOAWAY extends FrameType(0x7)
    case object WINDOW_UPDATE extends FrameType(0x8)
    case object CONTINUATION extends FrameType(0x9)

    val All =
      Array( // must start with id = 0 and don't have holes between ids
        DATA,
        HEADERS,
        PRIORITY,
        RST_STREAM,
        SETTINGS,
        PUSH_PROMISE,
        PING,
        GOAWAY,
        WINDOW_UPDATE,
        CONTINUATION).toSeq

    // make sure that lookup works and `All` ordering isn't broken
    All.foreach(f ⇒ require(f == byId(f.id), s"FrameType $f with id ${f.id} must be found"))

    def isKnownId(id: Int): Boolean = id < All.size
    def byId(id: Int): FrameType = All(id)
  }

  sealed abstract class SettingIdentifier(val id: Int) extends Product
  object SettingIdentifier {

    /**
     *  SETTINGS_HEADER_TABLE_SIZE (0x1):  Allows the sender to inform the
     *     remote endpoint of the maximum size of the header compression
     *     table used to decode header blocks, in octets.  The encoder can
     *     select any size equal to or less than this value by using
     *     signaling specific to the header compression format inside a
     *     header block (see [COMPRESSION]).  The initial value is 4,096
     *     octets.
     */
    case object SETTINGS_HEADER_TABLE_SIZE extends SettingIdentifier(0x1)

    /**
     * SETTINGS_ENABLE_PUSH (0x2):  This setting can be used to disable
     *    server push (Section 8.2).  An endpoint MUST NOT send a
     *    PUSH_PROMISE frame if it receives this parameter set to a value of
     *    0.  An endpoint that has both set this parameter to 0 and had it
     *    acknowledged MUST treat the receipt of a PUSH_PROMISE frame as a
     *    connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     *
     *    The initial value is 1, which indicates that server push is
     *    permitted.  Any value other than 0 or 1 MUST be treated as a
     *    connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    case object SETTINGS_ENABLE_PUSH extends SettingIdentifier(0x2)

    /**
     * SETTINGS_MAX_CONCURRENT_STREAMS (0x3):  Indicates the maximum number
     *    of concurrent streams that the sender will allow.  This limit is
     *    directional: it applies to the number of streams that the sender
     *    permits the receiver to create.  Initially, there is no limit to
     *    this value.  It is recommended that this value be no smaller than
     *    100, so as to not unnecessarily limit parallelism.
     *
     *    A value of 0 for SETTINGS_MAX_CONCURRENT_STREAMS SHOULD NOT be
     *    treated as special by endpoints.  A zero value does prevent the
     *    creation of new streams; however, this can also happen for any
     *    limit that is exhausted with active streams.  Servers SHOULD only
     *    set a zero value for short durations; if a server does not wish to
     *    accept requests, closing the connection is more appropriate.
     */
    case object SETTINGS_MAX_CONCURRENT_STREAMS extends SettingIdentifier(0x3)

    /**
     * SETTINGS_INITIAL_WINDOW_SIZE (0x4):  Indicates the sender's initial
     *    window size (in octets) for stream-level flow control.  The
     *    initial value is 2^16-1 (65,535) octets.
     *
     *    This setting affects the window size of all streams (see
     *    Section 6.9.2).
     *
     *    Values above the maximum flow-control window size of 2^31-1 MUST
     *    be treated as a connection error (Section 5.4.1) of type
     *    FLOW_CONTROL_ERROR.
     */
    case object SETTINGS_INITIAL_WINDOW_SIZE extends SettingIdentifier(0x4)

    /**
     * SETTINGS_MAX_FRAME_SIZE (0x5):  Indicates the size of the largest
     *    frame payload that the sender is willing to receive, in octets.
     *
     *    The initial value is 2^14 (16,384) octets.  The value advertised
     *    by an endpoint MUST be between this initial value and the maximum
     *    allowed frame size (2^24-1 or 16,777,215 octets), inclusive.
     *    Values outside this range MUST be treated as a connection error
     *    (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    case object SETTINGS_MAX_FRAME_SIZE extends SettingIdentifier(0x5)

    /**
     * SETTINGS_MAX_HEADER_LIST_SIZE (0x6):  This advisory setting informs a
     *    peer of the maximum size of header list that the sender is
     *    prepared to accept, in octets.  The value is based on the
     *    uncompressed size of header fields, including the length of the
     *    name and value in octets plus an overhead of 32 octets for each
     *    header field.
     *
     *    For any given request, a lower limit than what is advertised MAY
     *    be enforced.  The initial value of this setting is unlimited.
     */
    case object SETTINGS_MAX_HEADER_LIST_SIZE extends SettingIdentifier(0x6)

    val All =
      Array( // must start with id = 1 and don't have holes between ids
        SETTINGS_HEADER_TABLE_SIZE,
        SETTINGS_ENABLE_PUSH,
        SETTINGS_MAX_CONCURRENT_STREAMS,
        SETTINGS_INITIAL_WINDOW_SIZE,
        SETTINGS_MAX_FRAME_SIZE,
        SETTINGS_MAX_HEADER_LIST_SIZE).toSeq

    // make sure that lookup works and `All` ordering isn't broken
    All.foreach(f ⇒ require(f == byId(f.id) && isKnownId(f.id), s"SettingIdentifier $f with id ${f.id} must be found"))

    def isKnownId(id: Int): Boolean = id > 0 && id <= All.size
    def byId(id: Int): SettingIdentifier = All(id - 1)
  }

  sealed abstract class ErrorCode(val id: Int) extends Product
  object ErrorCode {
    /**
     * NO_ERROR (0x0):  The associated condition is not a result of an
     *    error.  For example, a GOAWAY might include this code to indicate
     *    graceful shutdown of a connection.
     */
    case object NO_ERROR extends ErrorCode(0x0)

    /**
     * PROTOCOL_ERROR (0x1):  The endpoint detected an unspecific protocol
     *    error.  This error is for use when a more specific error code is
     *    not available.
     */
    case object PROTOCOL_ERROR extends ErrorCode(0x1)

    /**
     * INTERNAL_ERROR (0x2):  The endpoint encountered an unexpected
     *    internal error.
     */
    case object INTERNAL_ERROR extends ErrorCode(0x2)

    /**
     * FLOW_CONTROL_ERROR (0x3):  The endpoint detected that its peer
     *    violated the flow-control protocol.
     */
    case object FLOW_CONTROL_ERROR extends ErrorCode(0x3)

    /**
     * SETTINGS_TIMEOUT (0x4):  The endpoint sent a SETTINGS frame but did
     *    not receive a response in a timely manner.  See Section 6.5.3
     *    ("Settings Synchronization").
     */
    case object SETTINGS_TIMEOUT extends ErrorCode(0x4)

    /**
     * STREAM_CLOSED (0x5):  The endpoint received a frame after a stream
     *    was half-closed.
     */
    case object STREAM_CLOSED extends ErrorCode(0x5)

    /**
     * FRAME_SIZE_ERROR (0x6):  The endpoint received a frame with an
     *    invalid size.
     */
    case object FRAME_SIZE_ERROR extends ErrorCode(0x6)

    /**
     * REFUSED_STREAM (0x7):  The endpoint refused the stream prior to
     *    performing any application processing (see Section 8.1.4 for
     *    details).
     */
    case object REFUSED_STREAM extends ErrorCode(0x7)

    /**
     * CANCEL (0x8):  Used by the endpoint to indicate that the stream is no
     *    longer needed.
     */
    case object CANCEL extends ErrorCode(0x8)

    /**
     * COMPRESSION_ERROR (0x9):  The endpoint is unable to maintain the
     *    header compression context for the connection.
     */
    case object COMPRESSION_ERROR extends ErrorCode(0x9)

    /**
     * CONNECT_ERROR (0xa):  The connection established in response to a
     *    CONNECT request (Section 8.3) was reset or abnormally closed.
     */
    case object CONNECT_ERROR extends ErrorCode(0xa)

    /**
     * ENHANCE_YOUR_CALM (0xb):  The endpoint detected that its peer is
     *    exhibiting a behavior that might be generating excessive load.
     */
    case object ENHANCE_YOUR_CALM extends ErrorCode(0xb)

    /**
     * INADEQUATE_SECURITY (0xc):  The underlying transport has properties
     *    that do not meet minimum security requirements (see Section 9.2).
     */
    case object INADEQUATE_SECURITY extends ErrorCode(0xc)

    /**
     * HTTP_1_1_REQUIRED (0xd):  The endpoint requires that HTTP/1.1 be used
     *    instead of HTTP/2.
     */
    case object HTTP_1_1_REQUIRED extends ErrorCode(0xd)

    val All =
      Array( // must start with id = 0 and don't have holes between ids
        NO_ERROR,
        PROTOCOL_ERROR,
        INTERNAL_ERROR,
        FLOW_CONTROL_ERROR,
        SETTINGS_TIMEOUT,
        STREAM_CLOSED,
        FRAME_SIZE_ERROR,
        REFUSED_STREAM,
        CANCEL,
        COMPRESSION_ERROR,
        CONNECT_ERROR,
        ENHANCE_YOUR_CALM,
        INADEQUATE_SECURITY,
        HTTP_1_1_REQUIRED
      ).toSeq

    // make sure that lookup works and `All` ordering isn't broken
    All.foreach(f ⇒ require(f == byId(f.id), s"ErrorCode $f with id ${f.id} must be found"))

    def isKnownId(id: Int): Boolean = id < All.size
    def byId(id: Int): ErrorCode = All(id)
  }

  /**
   *  The client connection preface starts with a sequence of 24 octets,
   *  which in hex notation is:
   *
   *     0x505249202a20485454502f322e300d0a0d0a534d0d0a0d0a
   */
  val ClientConnectionPreface =
    ByteString(
      "505249202a20485454502f322e300d0a0d0a534d0d0a0d0a"
        .grouped(2)
        .map(java.lang.Byte.parseByte(_, 16)).toSeq: _*)

  object Flags { flags ⇒
    val NO_FLAGS = new ByteFlag(0x0)

    val ACK = new ByteFlag(0x1)

    val END_STREAM = new ByteFlag(0x1) // same as ACK but used for other frame types
    val END_HEADERS = new ByteFlag(0x4)
    val PADDED = new ByteFlag(0x8)
    val PRIORITY = new ByteFlag(0x20)
  }
}
