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
import scala.collection.immutable.NumericRange

sealed abstract class CharPredicate extends (Char ⇒ Boolean) {
  import CharPredicate._

  /**
   * Determines wether this CharPredicate is an instance of the high-performance,
   * constant-time `CharPredicate.MaskBased` implementation.
   */
  def isMaskBased: Boolean = this.isInstanceOf[MaskBased]

  def asMaskBased: MaskBased =
    this match {
      case x: MaskBased ⇒ x
      case _            ⇒ sys.error("CharPredicate is not MaskBased")
    }

  def ++(that: CharPredicate): CharPredicate
  def ++(chars: Seq[Char]): CharPredicate
  def --(that: CharPredicate): CharPredicate
  def --(chars: Seq[Char]): CharPredicate

  def ++(char: Char): CharPredicate = this ++ (char :: Nil)
  def --(char: Char): CharPredicate = this -- (char :: Nil)
  def ++(chars: String): CharPredicate = this ++ chars.toCharArray
  def --(chars: String): CharPredicate = this -- chars.toCharArray

  def intersect(that: CharPredicate): CharPredicate

  def negated: CharPredicate = this match {
    case Empty ⇒ All
    case All   ⇒ Empty
    case x     ⇒ from(c ⇒ !x(c))
  }

  def matchesAny(string: String): Boolean = {
    @tailrec def rec(ix: Int): Boolean =
      if (ix == string.length) false else if (this(string charAt ix)) true else rec(ix + 1)
    rec(0)
  }

  def matchesAll(string: String): Boolean = {
    @tailrec def rec(ix: Int): Boolean =
      if (ix == string.length) true else if (!this(string charAt ix)) false else rec(ix + 1)
    rec(0)
  }

  def indexOfFirstMatch(string: String): Int = {
    @tailrec def rec(ix: Int): Int =
      if (ix == string.length) -1 else if (this(string charAt ix)) ix else rec(ix + 1)
    rec(0)
  }

  def indexOfFirstMismatch(string: String): Int = {
    @tailrec def rec(ix: Int): Int =
      if (ix == string.length) -1 else if (this(string charAt ix)) rec(ix + 1) else ix
    rec(0)
  }

  def firstMatch(string: String): Option[Char] =
    indexOfFirstMatch(string) match {
      case -1 ⇒ None
      case ix ⇒ Some(string charAt ix)
    }

  def firstMismatch(string: String): Option[Char] =
    indexOfFirstMismatch(string) match {
      case -1 ⇒ None
      case ix ⇒ Some(string charAt ix)
    }

  protected def or(that: Char ⇒ Boolean): CharPredicate =
    from(if (this == Empty) that else c ⇒ this(c) || that(c))
  protected def and(that: Char ⇒ Boolean): CharPredicate =
    if (this == Empty) Empty else from(c ⇒ this(c) && that(c))
  protected def andNot(that: Char ⇒ Boolean): CharPredicate =
    from(if (this == Empty) c ⇒ !that(c) else c ⇒ this(c) && !that(c))
}

object CharPredicate {
  val Empty: CharPredicate = MaskBased(0L, 0L)
  val All: CharPredicate = from(_ ⇒ true)
  val LowerAlpha = CharPredicate('a' to 'z')
  val UpperAlpha = CharPredicate('A' to 'Z')
  val Alpha = LowerAlpha ++ UpperAlpha
  val Digit = CharPredicate('0' to '9')
  val Digit19 = CharPredicate('1' to '9')
  val AlphaNum = Alpha ++ Digit
  val LowerHexLetter = CharPredicate('a' to 'f')
  val UpperHexLetter = CharPredicate('A' to 'F')
  val HexLetter = LowerHexLetter ++ UpperHexLetter
  val HexDigit = Digit ++ HexLetter
  val Visible = CharPredicate('\u0021' to '\u007e')
  val Printable = Visible ++ ' '

  def from(predicate: Char ⇒ Boolean): CharPredicate =
    predicate match {
      case x: CharPredicate ⇒ x
      case x                ⇒ General(x)
    }

  def apply(magnets: ApplyMagnet*): CharPredicate = (Empty /: magnets) { (a, m) ⇒ a ++ m.predicate }

  class ApplyMagnet(val predicate: CharPredicate)
  object ApplyMagnet {
    implicit def fromPredicate(predicate: Char ⇒ Boolean): ApplyMagnet = new ApplyMagnet(from(predicate))
    implicit def fromChar(c: Char): ApplyMagnet = fromChars(c :: Nil)
    implicit def fromCharArray(array: Array[Char]): ApplyMagnet = fromChars(array)
    implicit def fromString(chars: String): ApplyMagnet = fromChars(chars)
    implicit def fromChars(chars: Seq[Char]): ApplyMagnet =
      chars match {
        case _ if chars.size < 128 & !chars.exists(unmaskable) ⇒
          @tailrec def rec(ix: Int, result: CharPredicate): CharPredicate =
            if (ix == chars.length) result else rec(ix + 1, result ++ chars(ix))
          new ApplyMagnet(rec(0, Empty))
        case r: NumericRange[Char] ⇒ new ApplyMagnet(new RangeBased(r))
        case _                     ⇒ new ApplyMagnet(new ArrayBased(chars.toArray))
      }
  }

  ///////////////////////// PRIVATE ////////////////////////////

  private def unmaskable(c: Char) = c >= 128

  // efficient handling of 7bit-ASCII chars
  case class MaskBased private[CharPredicate] (lowMask: Long, highMask: Long) extends CharPredicate {
    def apply(c: Char): Boolean = {
      val mask = if (c < 64) lowMask else highMask
      ((1L << c) & ((c - 128) >> 31) & mask) != 0L // branchless for `(c < 128) && (mask & (1L << c) != 0)`
    }

    def ++(that: CharPredicate): CharPredicate = that match {
      case Empty                ⇒ this
      case _ if this == Empty   ⇒ that
      case MaskBased(low, high) ⇒ MaskBased(lowMask | low, highMask | high)
      case _                    ⇒ this or that
    }

    def ++(chars: Seq[Char]): CharPredicate = chars.foldLeft(this: CharPredicate) {
      case (_: MaskBased, c) if unmaskable(c)  ⇒ new ArrayBased(chars.toArray) ++ new ArrayBased(toArray)
      case (MaskBased(low, high), c) if c < 64 ⇒ MaskBased(low | 1L << c, high)
      case (MaskBased(low, high), c)           ⇒ MaskBased(low, high | 1L << c)
      case (x, _)                              ⇒ x // once the fold acc is not a MaskBased we are done
    }

    def --(that: CharPredicate): CharPredicate = that match {
      case Empty                ⇒ this
      case _ if this == Empty   ⇒ this
      case MaskBased(low, high) ⇒ MaskBased(lowMask & ~low, highMask & ~high)
      case _                    ⇒ this andNot that
    }

    def --(chars: Seq[Char]): CharPredicate =
      if (this != Empty) {
        chars.foldLeft(this: CharPredicate) {
          case (_: MaskBased, c) if unmaskable(c)  ⇒ this andNot new ArrayBased(chars.toArray)
          case (MaskBased(low, high), c) if c < 64 ⇒ MaskBased(low & ~(1L << c), high)
          case (MaskBased(low, high), c)           ⇒ MaskBased(low, high & ~(1L << c))
          case (x, _)                              ⇒ x // once the fold acc is not a MaskBased we are done
        }
      } else this

    def intersect(that: CharPredicate) = that match {
      case Empty                ⇒ Empty
      case _ if this == Empty   ⇒ Empty
      case MaskBased(low, high) ⇒ MaskBased(lowMask & low, highMask & high)
      case _                    ⇒ this and that
    }

    def size: Int = java.lang.Long.bitCount(lowMask) + java.lang.Long.bitCount(highMask)

    def toArray: Array[Char] = {
      val array = new Array[Char](size)
      getChars(array, 0)
      array
    }

    def getChars(array: Array[Char], startIx: Int): Unit = {
      @tailrec def rec(mask: Long, offset: Int, bit: Int, ix: Int): Int =
        if (bit < 64 && ix < array.length) {
          if ((mask & (1L << bit)) > 0) {
            array(ix) = (offset + bit).toChar
            rec(mask, offset, bit + 1, ix + 1)
          } else rec(mask, offset, bit + 1, ix)
        } else ix
      rec(highMask, 64, java.lang.Long.numberOfTrailingZeros(highMask),
        rec(lowMask, 0, java.lang.Long.numberOfTrailingZeros(lowMask), startIx))
    }

    override def toString(): String = "CharPredicate.MaskBased(" + new String(toArray) + ')'
  }

  class RangeBased private[CharPredicate] (private val range: NumericRange[Char]) extends CharPredicate {
    def apply(c: Char): Boolean = range contains c

    def ++(that: CharPredicate): CharPredicate = that match {
      case Empty ⇒ this
      case _     ⇒ this or that
    }

    def ++(other: Seq[Char]): CharPredicate = if (other.nonEmpty) this ++ CharPredicate(other) else this

    def --(that: CharPredicate): CharPredicate = that match {
      case Empty ⇒ this
      case _     ⇒ this andNot that
    }

    def --(other: Seq[Char]): CharPredicate = if (other.nonEmpty) this -- CharPredicate(other) else this

    def intersect(that: CharPredicate): CharPredicate = that match {
      case Empty ⇒ Empty
      case _     ⇒ this and that
    }

    override def toString(): String = s"CharPredicate.RangeBased(start = ${range.start}, end = ${range.end}, " +
      s"step = ${range.step.toInt}, inclusive = ${range.isInclusive})"
  }

  class ArrayBased private[CharPredicate] (private val chars: Array[Char]) extends CharPredicate {
    import java.util.Arrays._
    sort(chars)

    // TODO: switch to faster binary search algorithm with an adaptive pivot, e.g. http://ochafik.com/blog/?p=106
    def apply(c: Char): Boolean = binarySearch(chars, c) >= 0

    def ++(that: CharPredicate): CharPredicate = that match {
      case Empty         ⇒ this
      case x: ArrayBased ⇒ this ++ x.chars
      case _             ⇒ this or that
    }

    def ++(other: Seq[Char]): CharPredicate =
      if (other.nonEmpty) new ArrayBased((this -- other).chars ++ other.toArray[Char])
      else this

    def --(that: CharPredicate): CharPredicate = that match {
      case Empty         ⇒ this
      case x: ArrayBased ⇒ this -- x.chars
      case _             ⇒ this andNot that
    }

    def --(other: Seq[Char]): ArrayBased =
      if (other.nonEmpty) {
        val otherChars = other.toArray
        new ArrayBased(chars.filter(binarySearch(otherChars, _) < 0))
      } else this

    def intersect(that: CharPredicate): CharPredicate = that match {
      case Empty         ⇒ Empty
      case x: ArrayBased ⇒ new ArrayBased(chars.intersect(x.chars))
      case _             ⇒ this and that
    }

    override def toString(): String = "CharPredicate.ArrayBased(" + new String(chars) + ')'
  }

  case class General private[CharPredicate] (predicate: Char ⇒ Boolean) extends CharPredicate {
    def apply(c: Char) = predicate(c)

    def ++(that: CharPredicate): CharPredicate = that match {
      case Empty                  ⇒ this
      case General(thatPredicate) ⇒ from(c ⇒ predicate(c) || thatPredicate(c))
      case _                      ⇒ from(c ⇒ predicate(c) || that(c))
    }

    def ++(chars: Seq[Char]): CharPredicate =
      if (chars.nonEmpty) {
        val abp = new ArrayBased(chars.toArray)
        from(c ⇒ predicate(c) || abp(c))
      } else this

    def --(that: CharPredicate): CharPredicate = that match {
      case Empty                  ⇒ this
      case General(thatPredicate) ⇒ from(c ⇒ predicate(c) && !thatPredicate(c))
      case _                      ⇒ from(c ⇒ predicate(c) && !that(c))
    }

    def --(chars: Seq[Char]): CharPredicate =
      if (chars.nonEmpty) {
        val abp = new ArrayBased(chars.toArray)
        from(c ⇒ predicate(c) && !abp(c))
      } else this

    def intersect(that: CharPredicate) = that match {
      case Empty                  ⇒ Empty
      case General(thatPredicate) ⇒ from(c ⇒ predicate(c) && that(c))
      case _                      ⇒ this and that
    }

    override def toString(): String = "CharPredicate.General@" + System.identityHashCode(this)
  }
}
