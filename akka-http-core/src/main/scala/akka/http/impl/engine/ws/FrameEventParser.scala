/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.stream.impl.io.ByteStringParser
import akka.util.ByteString
import scala.annotation.tailrec
import akka.stream.Attributes

/**
 * Streaming parser for the WebSocket framing protocol as defined in RFC6455
 *
 * http://tools.ietf.org/html/rfc6455
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 * |                     Payload Data continued ...                |
 * +---------------------------------------------------------------+
 *
 * INTERNAL API
 */
private[http] object FrameEventParser extends ByteStringParser[FrameEvent] {
  import ByteStringParser._

  override def createLogic(attr: Attributes) = new ParsingLogic {
    startWith(ReadFrameHeader)

    trait Step extends ParseStep[FrameEvent] {
      override def onTruncation(): Unit = failStage(new ProtocolException("Data truncated"))
    }

    object ReadFrameHeader extends Step {
      override def parse(reader: ByteReader): ParseResult[FrameEvent] = {
        import Protocol._

        val flagsAndOp = reader.readByte()
        val maskAndLength = reader.readByte()

        val flags = flagsAndOp & FLAGS_MASK
        val op = flagsAndOp & OP_MASK

        val maskBit = (maskAndLength & MASK_MASK) != 0
        val length7 = maskAndLength & LENGTH_MASK

        val length =
          length7 match {
            case 126 ⇒ reader.readShortBE().toLong
            case 127 ⇒ reader.readLongBE()
            case x   ⇒ x.toLong
          }

        if (length < 0) throw new ProtocolException("Highest bit of 64bit length was set")

        val mask =
          if (maskBit) Some(reader.readIntBE())
          else None

        def isFlagSet(mask: Int): Boolean = (flags & mask) != 0
        val header =
          FrameHeader(
            Opcode.forCode(op.toByte),
            mask,
            length,
            fin = isFlagSet(FIN_MASK),
            rsv1 = isFlagSet(RSV1_MASK),
            rsv2 = isFlagSet(RSV2_MASK),
            rsv3 = isFlagSet(RSV3_MASK))

        val takeNow = (header.length min reader.remainingSize).toInt
        val thisFrameData = reader.take(takeNow)
        val noMoreData = thisFrameData.length == length

        val nextState =
          if (noMoreData) ReadFrameHeader
          else new ReadData(length - thisFrameData.length)

        ParseResult(Some(FrameStart(header, thisFrameData.compact)), nextState, true)
      }
    }

    class ReadData(_remaining: Long) extends Step {
      override def canWorkWithPartialData = true
      var remaining = _remaining
      override def parse(reader: ByteReader): ParseResult[FrameEvent] =
        if (reader.remainingSize < remaining) {
          remaining -= reader.remainingSize
          ParseResult(Some(FrameData(reader.takeAll(), lastPart = false)), this, true)
        } else {
          ParseResult(Some(FrameData(reader.take(remaining.toInt), lastPart = true)), ReadFrameHeader, true)
        }
    }
  }

  def mask(bytes: ByteString, _mask: Option[Int]): ByteString =
    _mask match {
      case Some(m) ⇒ mask(bytes, m)._1
      case None    ⇒ bytes
    }

  def mask(bytes: ByteString, mask: Int): (ByteString, Int) = {
    @tailrec def rec(bytes: Array[Byte], offset: Int, mask: Int): Int =
      if (offset >= bytes.length) mask
      else {
        val newMask = Integer.rotateLeft(mask, 8) // we cycle through the mask in BE order
        bytes(offset) = (bytes(offset) ^ (newMask & 0xff)).toByte
        rec(bytes, offset + 1, newMask)
      }

    val buffer = bytes.toArray[Byte]
    val newMask = rec(buffer, 0, mask)
    (ByteString(buffer), newMask)
  }

  def parseCloseCode(data: ByteString): Option[(Int, String)] = {
    def invalid(reason: String) = Some((Protocol.CloseCodes.ProtocolError, s"Peer sent illegal close frame ($reason)."))

    if (data.length >= 2) {
      val code = ((data(0) & 0xff) << 8) | (data(1) & 0xff)
      val message = Utf8Decoder.decode(data.drop(2))
      if (!Protocol.CloseCodes.isValid(code)) invalid(s"invalid close code '$code'")
      else if (message.isFailure) invalid("close reason message is invalid UTF8")
      else Some((code, message.get))
    } else if (data.length == 1) invalid("close code must be length 2 but was 1") // must be >= length 2 if not empty
    else None
  }

  override def toString: String = "FrameEventParser"
}
