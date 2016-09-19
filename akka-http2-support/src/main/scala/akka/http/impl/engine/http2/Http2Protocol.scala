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

  /**
   *  The client connection preface starts with a sequence of 24 octets,
   *  which in hex notation is:
   *
   *     0x505249202a20485454502f322e300d0a0d0a534d0d0a0d0a
   */
  val ConnectionPreface =
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
