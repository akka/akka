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

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import scala.annotation.tailrec

object CharUtils {
  /**
   * Returns the int value of a given hex digit char.
   * Note: this implementation is very fast (since it's branchless) and therefore
   * does not perform ANY range checks!
   */
  def hexValue(c: Char): Int = (c & 0x1f) + ((c >> 6) * 0x19) - 0x10

  /**
   * Computes the number of hex digits required to represent the given integer.
   * Leading zeros are not counted.
   */
  def numberOfHexDigits(l: Long): Int = (math.max(63 - java.lang.Long.numberOfLeadingZeros(l), 0) >> 2) + 1

  /**
   * Returns the lower-case hex digit corresponding to the last 4 bits of the given Long.
   * (fast branchless implementation)
   */
  def lowerHexDigit(long: Long): Char = lowerHexDigit_internal((long & 0x0FL).toInt)

  /**
   * Returns the lower-case hex digit corresponding to the last 4 bits of the given Int.
   * (fast branchless implementation)
   */
  def lowerHexDigit(int: Int): Char = lowerHexDigit_internal(int & 0x0F)

  private def lowerHexDigit_internal(i: Int) = (48 + i + (39 & ((9 - i) >> 31))).toChar

  /**
   * Returns the upper-case hex digit corresponding to the last 4 bits of the given Long.
   * (fast branchless implementation)
   */
  def upperHexDigit(long: Long): Char = upperHexDigit_internal((long & 0x0FL).toInt)

  /**
   * Returns the upper-case hex digit corresponding to the last 4 bits of the given Int.
   * (fast branchless implementation)
   */
  def upperHexDigit(int: Int): Char = upperHexDigit_internal(int & 0x0F)

  private def upperHexDigit_internal(i: Int) = (48 + i + (7 & ((9 - i) >> 31))).toChar

  /**
   * Efficiently converts the given long into an upper-case hex string.
   */
  def upperHexString(long: Long): String =
    appendUpperHexString(new JStringBuilder(numberOfHexDigits(long)), long).toString

  /**
   * Append the lower-case hex representation of the given long to the given StringBuilder.
   */
  def appendUpperHexString(sb: JStringBuilder, long: Long): JStringBuilder =
    if (long != 0) {
      @tailrec def putChar(shift: Int): JStringBuilder = {
        sb.append(upperHexDigit(long >>> shift))
        if (shift > 0) putChar(shift - 4) else sb
      }
      putChar((63 - java.lang.Long.numberOfLeadingZeros(long)) & 0xFC)
    } else sb.append('0')

  /**
   * Efficiently converts the given long into a lower-case hex string.
   */
  def lowerHexString(long: Long): String =
    appendLowerHexString(new JStringBuilder(numberOfHexDigits(long)), long).toString

  /**
   * Append the lower-case hex representation of the given long to the given StringBuilder.
   */
  def appendLowerHexString(sb: JStringBuilder, long: Long): JStringBuilder =
    if (long != 0) {
      @tailrec def putChar(shift: Int): JStringBuilder = {
        sb.append(lowerHexDigit(long >>> shift))
        if (shift > 0) putChar(shift - 4) else sb
      }
      putChar((63 - java.lang.Long.numberOfLeadingZeros(long)) & 0xFC)
    } else sb.append('0')

  /**
   * Returns a String representing the given long in signed decimal representation.
   */
  def signedDecimalString(long: Long): String = new String(signedDecimalChars(long))

  /**
   * Computes the number of characters required for the signed decimal representation of the given integer.
   */
  def numberOfDecimalDigits(long: Long): Int =
    if (long != Long.MinValue) _numberOfDecimalDigits(long) else 20

  private def _numberOfDecimalDigits(long: Long): Int = {
    def mul10(l: Long) = (l << 3) + (l << 1)
    @tailrec def len(test: Long, l: Long, result: Int): Int =
      if (test > l || test < 0) result else len(mul10(test), l, result + 1)
    if (long < 0) len(10, -long, 2) else len(10, long, 1)
  }

  val LongMinValueChars = "-9223372036854775808".toCharArray

  /**
   * Returns a char array representing the given long in signed decimal representation.
   */
  def signedDecimalChars(long: Long): Array[Char] =
    if (long != Long.MinValue) {
      val len = _numberOfDecimalDigits(long)
      val buf = new Array[Char](len)
      getSignedDecimalChars(long, len, buf)
      buf
    } else LongMinValueChars

  /**
   * Converts the given Long value into its signed decimal character representation.
   * The characters are placed into the given buffer *before* the given `endIndex` (exclusively).
   * CAUTION: This algorithm cannot deal with `Long.MinValue`, you'll need to special case this value!
   */
  def getSignedDecimalChars(long: Long, endIndex: Int, buf: Array[Char]): Unit = {
    def div10(i: Int) = {
      var q = (i << 3) + (i << 2)
      q += (q << 12) + (q << 8) + (q << 4) + i
      q >>>= 19
      q // 52429 * l / 524288 = l * 0.10000038146972656
    }
    def mul10(i: Int) = (i << 3) + (i << 1)
    def mul100(l: Long) = (l << 6) + (l << 5) + (l << 2)

    phase1(math.abs(long), endIndex)

    // for large numbers we bite the bullet of performing one division every two digits 
    @tailrec def phase1(l: Long, ix: Int): Unit =
      if (l > 65535L) {
        val q = l / 100
        val r = (l - mul100(q)).toInt
        val rq = div10(r)
        buf(ix - 2) = ('0' + rq).toChar
        buf(ix - 1) = ('0' + r - mul10(rq)).toChar
        phase1(q, ix - 2)
      } else phase2(l.toInt, ix)

    // for small numbers we can use the "fast-path"
    @tailrec def phase2(i: Int, ix: Int): Unit = {
      val q = div10(i)
      val r = i - mul10(q)
      buf(ix - 1) = ('0' + r).toChar
      if (q != 0) phase2(q, ix - 1)
      else if (long < 0) buf(ix - 2) = '-'
    }
  }

  /**
   * Efficiently lower-cases the given character.
   * Note: only works for 7-bit ASCII letters.
   */
  def toLowerCase(c: Char): Char = if (CharPredicate.UpperAlpha(c)) (c + 0x20).toChar else c

  /**
   * Efficiently upper-cases the given character.
   * Note: only works for 7-bit ASCII letters.
   */
  def toUpperCase(c: Char): Char = if (CharPredicate.LowerAlpha(c)) (c + 0x20).toChar else c

  def escape(c: Char): String = c match {
    case '\t'                           ⇒ "\\t"
    case '\r'                           ⇒ "\\r"
    case '\n'                           ⇒ "\\n"
    case EOI                            ⇒ "EOI"
    case x if Character.isISOControl(x) ⇒ "\\u%04x" format c.toInt
    case x                              ⇒ x.toString
  }

  val escapedChars = CharPredicate("\t\r\n", EOI, Character.isISOControl _)

  def escape(s: String): String =
    if (escapedChars.matchesAny(s)) s.flatMap(escape(_: Char)) else s
}
