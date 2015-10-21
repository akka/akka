/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.nio.ByteOrder

import akka.stream.scaladsl.{ Keep, BidiFlow, Flow }
import akka.stream.stage._
import akka.util.{ ByteIterator, ByteStringBuilder, ByteString }

import scala.annotation.tailrec

object Framing {

  /**
   * Creates a Flow that handles decoding a stream of unstructured byte chunks into a stream of frames where the
   * incoming chunk stream uses a specific byte-sequence to mark frame boundaries.
   *
   * The decoded frames will include the separator sequence. If this is not desired, this Flow can be augmented with a
   * simple ''map'' operation that removes this separator.
   *
   * If there are buffered bytes (an incomplete frame) when the input stream finishes and ''allowTruncation'' is set to
   * false then this Flow will fail the stream reporting a truncated frame.
   *
   * @param delimiter The byte sequence to be treated as the end of the frame.
   * @param allowTruncation If turned on, then when the last frame being decoded contains no valid delimiter this Flow
   *                        fails the stream instead of returning a truncated frame.
   * @param maximumFrameLength The maximum length of allowed frames while decoding. If the maximum length is
   *                           exceeded this Flow will fail the stream.
   * @return
   */
  def delimiter(delimiter: ByteString, maximumFrameLength: Int, allowTruncation: Boolean = false): Flow[ByteString, ByteString, Unit] =
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
   * @return
   */
  def lengthField(fieldLength: Int,
                  fieldOffset: Int = 0,
                  maximumFrameLength: Int,
                  byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): Flow[ByteString, ByteString, Unit] = {
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
   * @return
   */
  def simpleFramingProtocol(maximumMessageLength: Int): BidiFlow[ByteString, ByteString, ByteString, ByteString, Unit] = {
    val decoder = lengthField(4, 0, maximumMessageLength + 4, ByteOrder.BIG_ENDIAN).map(_.drop(4))
    val encoder = Flow[ByteString].transform(() ⇒ new PushStage[ByteString, ByteString] {

      override def onPush(message: ByteString, ctx: Context[ByteString]): SyncDirective = {
        if (message.size > maximumMessageLength)
          ctx.fail(new FramingException(s"Maximum allowed message size is $maximumMessageLength " +
            s"but tried to send ${message.size} bytes"))
        else {
          val header = ByteString(
            (message.size >> 24) & 0xFF,
            (message.size >> 16) & 0xFF,
            (message.size >> 8) & 0xFF,
            message.size & 0xFF)
          ctx.push(header ++ message)
        }
      }

    })

    BidiFlow.fromFlowsMat(encoder, decoder)(Keep.left)
  }

  private trait IntDecoder {
    def decode(bs: ByteIterator): Int
  }

  class FramingException(msg: String) extends RuntimeException(msg)

  private class BigEndianCodec(val length: Int) extends IntDecoder {
    override def decode(bs: ByteIterator): Int = {
      var count = length
      var decoded = 0
      while (count > 0) {
        decoded <<= 8
        decoded |= bs.next().toInt & 0xFF
        count -= 1
      }
      decoded
    }
  }

  private class LittleEndianCodec(val length: Int) extends IntDecoder {
    private val highestOctet = (length - 1) * 8
    private val Mask = (1 << (length * 8)) - 1

    override def decode(bs: ByteIterator): Int = {
      var count = length
      var decoded = 0
      while (count > 0) {
        decoded >>>= 8
        decoded += (bs.next().toInt & 0xFF) << highestOctet
        count -= 1
      }
      decoded & Mask
    }
  }

  private class DelimiterFramingStage(val separatorBytes: ByteString, val maximumLineBytes: Int, val allowTruncation: Boolean)
    extends PushPullStage[ByteString, ByteString] {
    private val firstSeparatorByte = separatorBytes.head
    private var buffer = ByteString.empty
    private var nextPossibleMatch = 0
    private var finishing = false

    override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
      buffer ++= chunk
      doParse(ctx)
    }

    override def onPull(ctx: Context[ByteString]): SyncDirective = {
      doParse(ctx)
    }

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
      if (buffer.nonEmpty) ctx.absorbTermination()
      else ctx.finish()
    }

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
          if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size)
            == separatorBytes) {
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

  private class LengthFieldFramingStage(
    val lengthFieldLength: Int,
    val lengthFieldOffset: Int,
    val maximumFrameLength: Int,
    val byteOrder: ByteOrder) extends PushPullStage[ByteString, ByteString] {
    private var buffer = ByteString.empty
    private val minimumChunkSize = lengthFieldOffset + lengthFieldLength
    private val intDecoder: IntDecoder = byteOrder match {
      case ByteOrder.BIG_ENDIAN    ⇒ new BigEndianCodec(lengthFieldLength)
      case ByteOrder.LITTLE_ENDIAN ⇒ new LittleEndianCodec(lengthFieldLength)
    }
    private var frameSize = Int.MaxValue

    private def parseLength: Int = intDecoder.decode(buffer.iterator.drop(lengthFieldOffset))

    private def tryPull(ctx: Context[ByteString]): SyncDirective = {
      if (ctx.isFinishing) ctx.fail(new FramingException(
        "Stream finished but there was a truncated final frame in the buffer"))
      else ctx.pull()
    }

    override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
      buffer ++= chunk
      doParse(ctx)
    }

    override def onPull(ctx: Context[ByteString]): SyncDirective = {
      doParse(ctx)
    }

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
      if (buffer.nonEmpty) ctx.absorbTermination()
      else ctx.finish()
    }

    private def emitFrame(ctx: Context[ByteString]): SyncDirective = {
      val parsedFrame = buffer.take(frameSize).compact
      buffer = buffer.drop(frameSize)
      frameSize = Int.MaxValue
      if (ctx.isFinishing && buffer.isEmpty) ctx.pushAndFinish(parsedFrame)
      else ctx.push(parsedFrame)
    }

    private def doParse(ctx: Context[ByteString]): SyncDirective = {
      if (buffer.size >= frameSize) {
        emitFrame(ctx)
      } else if (buffer.size >= minimumChunkSize) {
        frameSize = parseLength + minimumChunkSize
        if (frameSize > maximumFrameLength)
          ctx.fail(new FramingException(s"Maximum allowed frame size is $maximumFrameLength " +
            s"but decoded frame header reported size $frameSize"))
        else if (buffer.size >= frameSize)
          emitFrame(ctx)
        else tryPull(ctx)
      } else tryPull(ctx)
    }

    override def postStop(): Unit = buffer = null
  }

}
