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
 * that avoids a separate decoding step.
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
    bytes.slice(start, end - start).decodeString(StandardCharsets.UTF_8)
  override def sliceCharArray(start: Int, end: Int): Array[Char] =
    StandardCharsets.UTF_8.decode(bytes.slice(start, end).asByteBuffer).array()
}

object SprayJsonByteStringParserInput {
  private final val EOI = '\uFFFF'
  // compile-time constant
  private final val ErrorChar = '\uFFFD' // compile-time constant, universal UTF-8 replacement character '�'
}
