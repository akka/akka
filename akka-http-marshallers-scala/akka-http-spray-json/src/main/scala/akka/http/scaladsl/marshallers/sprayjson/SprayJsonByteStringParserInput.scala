/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import java.nio.{ ByteBuffer, CharBuffer }
import java.nio.charset.{ Charset, StandardCharsets }

import akka.util.ByteString
import spray.json.ParserInput.DefaultParserInput
import scala.annotation.tailrec

/**
 * ParserInput reading directly off a ByteString. (Based on the ByteArrayBasedParserInput)
 * This avoids a separate decoding step but assumes that each byte represents exactly one character,
 * which is encoded by ISO-8859-1!
 * You can therefore use this ParserInput type only if you know that all input will be `ISO-8859-1`-encoded,
 * or only contains 7-bit ASCII characters (which is a subset of ISO-8859-1)!
 *
 * Note that this ParserInput type will NOT work with general `UTF-8`-encoded input as this can contain
 * character representations spanning multiple bytes. However, if you know that your input will only ever contain
 * 7-bit ASCII characters (0x00-0x7F) then UTF-8 is fine, since the first 127 UTF-8 characters are
 * encoded with only one byte that is identical to 7-bit ASCII and ISO-8859-1.
 */
final class SprayJsonByteStringParserInput(bytes: ByteString) extends DefaultParserInput {

  import SprayJsonByteStringParserInput._

  private[this] val byteBuffer = ByteBuffer.allocate(4)
  private[this] val charBuffer = CharBuffer.allocate(1)

  private[this] val decoder = Charset.forName("UTF-8").newDecoder()

  override def nextChar() = {
    _cursor += 1
    if (_cursor < bytes.length) (bytes(_cursor) & 0xFF).toChar else EOI
  }

  override def nextUtf8Char() = {
    @tailrec def decode(byte: Byte, remainingBytes: Int): Char = {
      byteBuffer.put(byte)
      if (remainingBytes > 0) {
        _cursor += 1
        if (_cursor < bytes.length) decode(bytes(_cursor), remainingBytes - 1) else ErrorChar
      } else {
        byteBuffer.flip()
        val coderResult = decoder.decode(byteBuffer, charBuffer, false)
        charBuffer.flip()
        val result = if (coderResult.isUnderflow & charBuffer.hasRemaining) charBuffer.get() else ErrorChar
        byteBuffer.clear()
        charBuffer.clear()
        result
      }
    }

    _cursor += 1
    if (_cursor < bytes.length) {
      val byte = bytes(_cursor)
      if (byte >= 0) byte.toChar // 7-Bit ASCII
      else if ((byte & 0xE0) == 0xC0) decode(byte, 1) // 2-byte UTF-8 sequence
      else if ((byte & 0xF0) == 0xE0) decode(byte, 2) // 3-byte UTF-8 sequence
      else if ((byte & 0xF8) == 0xF0) decode(byte, 3) // 4-byte UTF-8 sequence, will probably produce an (unsupported) surrogate pair
      else ErrorChar
    } else EOI
  }

  override def length: Int = bytes.size
  override def sliceString(start: Int, end: Int): String =
    bytes.slice(start, end - start).decodeString(StandardCharsets.ISO_8859_1)
  override def sliceCharArray(start: Int, end: Int): Array[Char] =
    StandardCharsets.ISO_8859_1.decode(bytes.slice(start, end).asByteBuffer).array()
}

object SprayJsonByteStringParserInput {
  private final val EOI = '\uFFFF'
  // compile-time constant
  private final val ErrorChar = '\uFFFD' // compile-time constant, universal UTF-8 replacement character '�'
}
