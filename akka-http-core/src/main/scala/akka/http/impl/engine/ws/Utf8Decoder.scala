/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.util.ByteString

import scala.util.Try

/**
 * A Utf8 -> Utf16 (= Java char) decoder.
 *
 * This decoder is based on the one of Bjoern Hoehrmann from
 *
 * http://bjoern.hoehrmann.de/utf-8/decoder/dfa/
 *
 * which is licensed under this license:
 *
 * Copyright (C) 2008-2016 Bjoern Hoehrmann <bjoern@hoehrmann.de>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * INTERNAL API
 */
private[http] object Utf8Decoder extends StreamingCharsetDecoder {
  private[this] val Utf8Accept = 0
  private[this] val Utf8Reject = 12

  val characterClasses =
    Array[Byte](
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
      10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)

  val states =
    Array[Byte](
      0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
      12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 24, 12, 12,
      12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12,
      12, 12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12,
      12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12)

  def create(): StreamingCharsetDecoderInstance =
    new StreamingCharsetDecoderInstance {
      var currentCodePoint = 0
      var currentState = Utf8Accept

      def decode(bytes: ByteString, endOfInput: Boolean): Try[String] = Try {
        val result = new StringBuilder(bytes.size)
        val length = bytes.size

        def step(byte: Int): Unit = {
          val chClass = characterClasses(byte)
          currentCodePoint =
            if (currentState == Utf8Accept) // first byte
              (0xff >> chClass) & byte // take as much bits as the characterClass says
            else // continuation byte
              (0x3f & byte) | (currentCodePoint << 6) // take 6 bits
          currentState = states(currentState + chClass)

          currentState match {
            case Utf8Accept ⇒
              if (currentCodePoint <= 0xffff)
                // fits in single UTF-16 char
                result.append(currentCodePoint.toChar)
              else {
                // create surrogate pair
                result.append((0xD7C0 + (currentCodePoint >> 10)).toChar)
                result.append((0xDC00 + (currentCodePoint & 0x3FF)).toChar)
              }
            case Utf8Reject ⇒ fail("Invalid UTF-8 input")
            case _          ⇒ // valid intermediate state, need more input
          }
        }

        var offset = 0
        while (offset < length) {
          step(bytes(offset) & 0xff)
          offset += 1
        }

        if (endOfInput && currentState != Utf8Accept) fail("Truncated UTF-8 input")
        else
          result.toString()
      }

      def fail(msg: String): Nothing = throw new IllegalArgumentException(msg)
    }
}

private[http] trait StreamingCharsetDecoder {
  def create(): StreamingCharsetDecoderInstance
  def decode(bytes: ByteString): Try[String] = create().decode(bytes, endOfInput = true)
}
private[http] trait StreamingCharsetDecoderInstance {
  def decode(bytes: ByteString, endOfInput: Boolean): Try[String]
}
