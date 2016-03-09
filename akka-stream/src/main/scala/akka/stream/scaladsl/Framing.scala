/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.nio.ByteOrder

import akka.NotUsed
import akka.stream.stage._
import akka.util.{ ByteIterator, ByteString }

import scala.annotation.tailrec

object Framing {

  /**
   * Creates a Flow that handles decoding a stream of unstructured byte chunks into a stream of frames where the
   * incoming chunk stream uses a specific byte-sequence to mark frame boundaries.
   *
   * The decoded frames will not include the separator sequence.
   *
   * If there are buffered bytes (an incomplete frame) when the input stream finishes and ''allowTruncation'' is set to
   * false then this Flow will fail the stream reporting a truncated frame.
   *
   * @param delimiter The byte sequence to be treated as the end of the frame.
   * @param allowTruncation If `false`, then when the last frame being decoded contains no valid delimiter this Flow
   *                        fails the stream instead of returning a truncated frame.
   * @param maximumFrameLength The maximum length of allowed frames while decoding. If the maximum length is
   *                           exceeded this Flow will fail the stream.
   */
  def delimiter(delimiter: ByteString, maximumFrameLength: Int, allowTruncation: Boolean = false): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].transform(() ⇒ new DelimiterFramingStage(delimiter, maximumFrameLength, allowTruncation))
      .named("delimiterFraming")

  /**
   * Creates a Flow that decodes an incoming stream of unstructured byte chunks into a stream of frames, assuming that
   * incoming frames have a field that encodes their length.
   *
   * If the input stream finishes before the last frame has been fully decoded this Flow will fail the stream reporting
   * a truncated frame.
   *
   * @param fieldLength The length of the "size" field in bytes
   * @param fieldOffset The offset of the field from the beginning of the frame in bytes
   * @param maximumFrameLength The maximum length of allowed frames while decoding. If the maximum length is exceeded
   *                           this Flow will fail the stream. This length *includes* the header (i.e the offset and
   *                           the length of the size field)
   * @param byteOrder The ''ByteOrder'' to be used when decoding the field
   */
  def lengthField(fieldLength: Int,
                  fieldOffset: Int = 0,
                  maximumFrameLength: Int,
                  byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): Flow[ByteString, ByteString, NotUsed] = {
    require(fieldLength >= 1 && fieldLength <= 4, "Length field length must be 1, 2, 3 or 4.")
    Flow[ByteString].transform(() ⇒ new LengthFieldFramingStage(fieldLength, fieldOffset, maximumFrameLength, byteOrder))
      .named("lengthFieldFraming")
  }

  /**
   * Returns a BidiFlow that implements a simple framing protocol. This is a convenience wrapper over [[Framing#lengthField]]
   * and simply attaches a length field header of four bytes (using big endian encoding) to outgoing messages, and decodes
   * such messages in the inbound direction. The decoded messages do not contain the header.
   *
   * This BidiFlow is useful if a simple message framing protocol is needed (for example when TCP is used to send
   * individual messages) but no compatibility with existing protocols is necessary.
   *
   * The encoded frames have the layout
   * {{{
   *   [4 bytes length field, Big Endian][User Payload]
   * }}}
   * The length field encodes the length of the user payload excluding the header itself.
   *
   * @param maximumMessageLength Maximum length of allowed messages. If sent or received messages exceed the configured
   *                             limit this BidiFlow will fail the stream. The header attached by this BidiFlow are not
   *                             included in this limit.
   */
  def simpleFramingProtocol(maximumMessageLength: Int): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    BidiFlow.fromFlowsMat(simpleFramingProtocolEncoder(maximumMessageLength), simpleFramingProtocolDecoder(maximumMessageLength))(Keep.left)
  }

  /**
   * Protocol decoder that is used by [[Framing#simpleFramingProtocol]]
   */
  def simpleFramingProtocolDecoder(maximumMessageLength: Int): Flow[ByteString, ByteString, NotUsed] =
    lengthField(4, 0, maximumMessageLength + 4, ByteOrder.BIG_ENDIAN).map(_.drop(4))

  /**
   * Protocol encoder that is used by [[Framing#simpleFramingProtocol]]
   */
  def simpleFramingProtocolEncoder(maximumMessageLength: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].transform(() ⇒ new PushStage[ByteString, ByteString] {
      override def onPush(message: ByteString, ctx: Context[ByteString]): SyncDirective = {
        val msgSize = message.size
        if (msgSize > maximumMessageLength)
          ctx.fail(new FramingException(s"Maximum allowed message size is $maximumMessageLength but tried to send $msgSize bytes"))
        else {
          val header = ByteString((msgSize >> 24) & 0xFF, (msgSize >> 16) & 0xFF, (msgSize >> 8) & 0xFF, msgSize & 0xFF)
          ctx.push(header ++ message)
        }
      }
    })

  class FramingException(msg: String) extends RuntimeException(msg)

  private final val bigEndianDecoder: (ByteIterator, Int) ⇒ Int = (bs, length) ⇒ {
    var count = length
    var decoded = 0
    while (count > 0) {
      decoded <<= 8
      decoded |= bs.next().toInt & 0xFF
      count -= 1
    }
    decoded
  }

  private final val littleEndianDecoder: (ByteIterator, Int) ⇒ Int = (bs, length) ⇒ {
    val highestOctet = (length - 1) << 3
    val Mask = ((1L << (length << 3)) - 1).toInt
    var count = length
    var decoded = 0
    while (count > 0) {
      decoded >>>= 8
      decoded += (bs.next().toInt & 0xFF) << highestOctet
      count -= 1
    }
    decoded & Mask
  }

  private class DelimiterFramingStage(val separatorBytes: ByteString, val maximumLineBytes: Int, val allowTruncation: Boolean)
    extends PushPullStage[ByteString, ByteString] {
    private val firstSeparatorByte = separatorBytes.head
    private var buffer = ByteString.empty
    private var nextPossibleMatch = 0

    override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
      buffer ++= chunk
      doParse(ctx)
    }

    override def onPull(ctx: Context[ByteString]): SyncDirective = doParse(ctx)

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective =
      if (buffer.nonEmpty) ctx.absorbTermination()
      else ctx.finish()

    private def tryPull(ctx: Context[ByteString]): SyncDirective = {
      if (ctx.isFinishing) {
        if (allowTruncation) ctx.pushAndFinish(buffer)
        else
          ctx.fail(new FramingException(
            "Stream finished but there was a truncated final frame in the buffer"))
      } else ctx.pull()
    }

    @tailrec
    private def doParse(ctx: Context[ByteString]): SyncDirective = {
      val possibleMatchPos = buffer.indexOf(firstSeparatorByte, from = nextPossibleMatch)
      if (possibleMatchPos > maximumLineBytes)
        ctx.fail(new FramingException(s"Read ${buffer.size} bytes " +
          s"which is more than $maximumLineBytes without seeing a line terminator"))
      else {
        if (possibleMatchPos == -1) {
          // No matching character, we need to accumulate more bytes into the buffer
          nextPossibleMatch = buffer.size
          tryPull(ctx)
        } else if (possibleMatchPos + separatorBytes.size > buffer.size) {
          // We have found a possible match (we found the first character of the terminator
          // sequence) but we don't have yet enough bytes. We remember the position to
          // retry from next time.
          nextPossibleMatch = possibleMatchPos
          tryPull(ctx)
        } else {
          if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size) == separatorBytes) {
            // Found a match
            val parsedFrame = buffer.slice(0, possibleMatchPos).compact
            buffer = buffer.drop(possibleMatchPos + separatorBytes.size)
            nextPossibleMatch = 0
            if (ctx.isFinishing && buffer.isEmpty) ctx.pushAndFinish(parsedFrame)
            else ctx.push(parsedFrame)
          } else {
            nextPossibleMatch += 1
            doParse(ctx)
          }
        }
      }
    }

    override def postStop(): Unit = buffer = null
  }

  private final class LengthFieldFramingStage(
    val lengthFieldLength: Int,
    val lengthFieldOffset: Int,
    val maximumFrameLength: Int,
    val byteOrder: ByteOrder) extends PushPullStage[ByteString, ByteString] {
    private var buffer = ByteString.empty
    private var frameSize = Int.MaxValue
    private val minimumChunkSize = lengthFieldOffset + lengthFieldLength
    private val intDecoder = byteOrder match {
      case ByteOrder.BIG_ENDIAN    ⇒ bigEndianDecoder
      case ByteOrder.LITTLE_ENDIAN ⇒ littleEndianDecoder
    }

    private def tryPull(ctx: Context[ByteString]): SyncDirective =
      if (ctx.isFinishing) ctx.fail(new FramingException("Stream finished but there was a truncated final frame in the buffer"))
      else ctx.pull()

    override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
      buffer ++= chunk
      doParse(ctx)
    }

    override def onPull(ctx: Context[ByteString]): SyncDirective = doParse(ctx)

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective =
      if (buffer.nonEmpty) ctx.absorbTermination()
      else ctx.finish()

    private def doParse(ctx: Context[ByteString]): SyncDirective = {
      def emitFrame(ctx: Context[ByteString]): SyncDirective = {
        val parsedFrame = buffer.take(frameSize).compact
        buffer = buffer.drop(frameSize)
        frameSize = Int.MaxValue
        if (ctx.isFinishing && buffer.isEmpty) ctx.pushAndFinish(parsedFrame)
        else ctx.push(parsedFrame)
      }

      val bufSize = buffer.size
      if (bufSize >= frameSize) emitFrame(ctx)
      else if (bufSize >= minimumChunkSize) {
        val parsedLength = intDecoder(buffer.iterator.drop(lengthFieldOffset), lengthFieldLength)
        frameSize = parsedLength + minimumChunkSize
        if (frameSize > maximumFrameLength)
          ctx.fail(new FramingException(s"Maximum allowed frame size is $maximumFrameLength but decoded frame header reported size $frameSize"))
        else if (bufSize >= frameSize) emitFrame(ctx)
        else tryPull(ctx)
      } else tryPull(ctx)
    }

    override def postStop(): Unit = buffer = null
  }

}
