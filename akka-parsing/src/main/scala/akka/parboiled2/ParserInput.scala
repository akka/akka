/*
 * Copyright (C) 2009-2016 Mathias Doenitz, Alexander Myltsev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.parboiled2

import scala.annotation.tailrec
import java.nio.ByteBuffer

trait ParserInput {
  /**
   * Returns the character at the given (zero-based) index.
   * Note: this method is hot and should be small and efficient.
   * A range-check is not required for the parser to work correctly.
   */
  def charAt(ix: Int): Char

  /**
   * The number of characters in this input.
   * Note: this method is hot and should be small and efficient.
   */
  def length: Int

  /**
   * Returns the characters between index `start` (inclusively) and `end` (exclusively) as a `String`.
   */
  def sliceString(start: Int, end: Int): String

  /**
   * Returns the characters between index `start` (inclusively) and `end` (exclusively) as an `Array[Char]`.
   */
  def sliceCharArray(start: Int, end: Int): Array[Char]

  /**
   * Gets the input line with the given number as a String.
   * Note: the first line is line number one!
   */
  def getLine(line: Int): String
}

object ParserInput {
  val Empty = apply(Array.empty[Byte])

  implicit def apply(bytes: Array[Byte]): ByteArrayBasedParserInput = new ByteArrayBasedParserInput(bytes)
  implicit def apply(bytes: Array[Byte], endIndex: Int): ByteArrayBasedParserInput = new ByteArrayBasedParserInput(bytes, endIndex)
  implicit def apply(string: String): StringBasedParserInput = new StringBasedParserInput(string)
  implicit def apply(chars: Array[Char]): CharArrayBasedParserInput = new CharArrayBasedParserInput(chars)
  implicit def apply(chars: Array[Char], endIndex: Int): CharArrayBasedParserInput = new CharArrayBasedParserInput(chars, endIndex)

  abstract class DefaultParserInput extends ParserInput {
    def getLine(line: Int): String = {
      @tailrec def rec(ix: Int, lineStartIx: Int, lineNr: Int): String =
        if (ix < length)
          if (charAt(ix) == '\n')
            if (lineNr < line) rec(ix + 1, ix + 1, lineNr + 1)
            else sliceString(lineStartIx, ix)
          else rec(ix + 1, lineStartIx, lineNr)
        else if (lineNr == line) sliceString(lineStartIx, ix) else ""
      rec(ix = 0, lineStartIx = 0, lineNr = 1)
    }
  }

  /**
   * ParserInput reading directly off a byte array.
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
  class ByteArrayBasedParserInput(bytes: Array[Byte], endIndex: Int = 0) extends DefaultParserInput {
    val length = if (endIndex <= 0 || endIndex > bytes.length) bytes.length else endIndex
    def charAt(ix: Int) = (bytes(ix) & 0xFF).toChar
    def sliceString(start: Int, end: Int) = new String(bytes, start, end - start, `ISO-8859-1`)
    def sliceCharArray(start: Int, end: Int) =
      `ISO-8859-1`.decode(ByteBuffer.wrap(java.util.Arrays.copyOfRange(bytes, start, end))).array()
  }

  class StringBasedParserInput(string: String) extends DefaultParserInput {
    def charAt(ix: Int) = string.charAt(ix)
    def length = string.length
    def sliceString(start: Int, end: Int) = string.substring(start, end)
    def sliceCharArray(start: Int, end: Int) = {
      val chars = new Array[Char](end - start)
      string.getChars(start, end, chars, 0)
      chars
    }
  }

  class CharArrayBasedParserInput(chars: Array[Char], endIndex: Int = 0) extends DefaultParserInput {
    val length = if (endIndex <= 0 || endIndex > chars.length) chars.length else endIndex
    def charAt(ix: Int) = chars(ix)
    def sliceString(start: Int, end: Int) = new String(chars, start, end - start)
    def sliceCharArray(start: Int, end: Int) = java.util.Arrays.copyOfRange(chars, start, end)
  }
}