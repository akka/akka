/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.impl.util.{ ByteReader, ByteStringParserStage }
import akka.stream.stage.{ StageState, SyncDirective, Context }
import akka.util.ByteString

import scala.annotation.tailrec

/**
 * Streaming parser for the Websocket framing protocol as defined in RFC6455
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
private[http] class FrameEventParser extends ByteStringParserStage[FrameEvent] {
  protected def onTruncation(ctx: Context[FrameEvent]): SyncDirective =
    ctx.fail(new ProtocolException("Data truncated"))

  def initial: StageState[ByteString, FrameEvent] = ReadFrameHeader

  object ReadFrameHeader extends ByteReadingState {
    def read(reader: ByteReader, ctx: Context[FrameEvent]): SyncDirective = {
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

      if (length < 0) ctx.fail(new ProtocolException("Highest bit of 64bit length was set"))

      val mask =
        if (maskBit) Some(reader.readIntBE())
        else None

      def isFlagSet(mask: Int): Boolean = (flags & mask) != 0
      val header =
        FrameHeader(Opcode.forCode(op.toByte),
          mask,
          length,
          fin = isFlagSet(FIN_MASK),
          rsv1 = isFlagSet(RSV1_MASK),
          rsv2 = isFlagSet(RSV2_MASK),
          rsv3 = isFlagSet(RSV3_MASK))

      val data = reader.remainingData
      val takeNow = (header.length min Int.MaxValue).toInt
      val thisFrameData = data.take(takeNow)
      val remaining = data.drop(takeNow)

      val nextState =
        if (thisFrameData.length == length) ReadFrameHeader
        else readData(length - thisFrameData.length)

      pushAndBecomeWithRemaining(FrameStart(header, thisFrameData), nextState, remaining, ctx)
    }
  }

  def readData(_remaining: Long): State =
    new State {
      var remaining = _remaining
      def onPush(elem: ByteString, ctx: Context[FrameEvent]): SyncDirective =
        if (elem.size < remaining) {
          remaining -= elem.size
          ctx.push(FrameData(elem, lastPart = false))
        } else {
          assert(remaining <= Int.MaxValue) // safe because, remaining <= elem.size <= Int.MaxValue
          val frameData = elem.take(remaining.toInt)
          val remainingData = elem.drop(remaining.toInt)

          pushAndBecomeWithRemaining(FrameData(frameData, lastPart = true), ReadFrameHeader, remainingData, ctx)
        }
    }

  def becomeWithRemaining(nextState: State, remainingData: ByteString, ctx: Context[FrameEvent]): SyncDirective = {
    become(nextState)
    nextState.onPush(remainingData, ctx)
  }
  def pushAndBecomeWithRemaining(elem: FrameEvent, nextState: State, remainingData: ByteString, ctx: Context[FrameEvent]): SyncDirective =
    if (remainingData.isEmpty) {
      become(nextState)
      ctx.push(elem)
    } else {
      become(waitForPull(nextState, remainingData))
      ctx.push(elem)
    }

  def waitForPull(nextState: State, remainingData: ByteString): State =
    new State {
      def onPush(elem: ByteString, ctx: Context[FrameEvent]): SyncDirective =
        throw new IllegalStateException("Mustn't push in this state")

      override def onPull(ctx: Context[FrameEvent]): SyncDirective = {
        become(nextState)
        nextState.onPush(remainingData, ctx)
      }
    }
}

object FrameEventParser {
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

  def parseCloseCode(data: ByteString): Option[Int] =
    if (data.length >= 2) {
      val code = ((data(0) & 0xff) << 8) | (data(1) & 0xff)
      if (Protocol.CloseCodes.isValid(code)) Some(code)
      else Some(Protocol.CloseCodes.ProtocolError)
    } else if (data.length == 1) Some(Protocol.CloseCodes.ProtocolError) // must be >= length 2 if not empty
    else None
}
