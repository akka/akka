/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.annotation.tailrec
import language.implicitConversions

// bimorphic implementation of character sets
// FIXME: replace with implementation from parboiled2
sealed abstract class CharPredicate extends (Char ⇒ Boolean) {
  import CharPredicate._

  def ++(that: CharPredicate): CharPredicate
  def ++(chars: Seq[Char]): CharPredicate
  def --(that: CharPredicate): CharPredicate
  def --(chars: Seq[Char]): CharPredicate

  def ++(chars: Char): CharPredicate = this ++ (chars :: Nil)
  def --(chars: Char): CharPredicate = this -- (chars :: Nil)
  def ++(chars: String): CharPredicate = this ++ chars.toCharArray
  def --(chars: String): CharPredicate = this -- chars.toCharArray

  def unary_!(): CharPredicate = this match {
    case Empty ⇒ All
    case All   ⇒ Empty
    case _     ⇒ from(c ⇒ !apply(c))
  }

  def matchAny(string: String): Boolean = {
    @tailrec def rec(ix: Int = 0): Boolean =
      if (ix == string.length) false else if (apply(string.charAt(ix))) true else rec(ix + 1)
    rec()
  }

  def matchAll(string: String): Boolean = {
    @tailrec def rec(ix: Int = 0): Boolean =
      if (ix == string.length) true else if (!apply(string.charAt(ix))) false else rec(ix + 1)
    rec()
  }

  def indexOfFirstMatch(string: String): Int = {
    @tailrec def rec(ix: Int = 0): Int =
      if (ix == string.length) -1 else if (apply(string.charAt(ix))) ix else rec(ix + 1)
    rec()
  }

  def indexOfFirstMismatch(string: String): Int = {
    @tailrec def rec(ix: Int = 0): Int =
      if (ix == string.length) -1 else if (apply(string.charAt(ix))) rec(ix + 1) else ix
    rec()
  }

  def firstMatch(string: String): Option[Char] =
    indexOfFirstMatch(string) match {
      case -1 ⇒ None
      case ix ⇒ Some(string.charAt(ix))
    }

  def firstMismatch(string: String): Option[Char] =
    indexOfFirstMismatch(string) match {
      case -1 ⇒ None
      case ix ⇒ Some(string.charAt(ix))
    }
}

object CharPredicate {
  val Empty: CharPredicate = CharMask(0L, 0L)
  val All: CharPredicate = from(_ ⇒ true)

  def apply(magnets: ApplyMagnet*): CharPredicate = {
    @tailrec def rec(ix: Int = 0, result: CharPredicate = Empty): CharPredicate =
      if (ix == magnets.length) result else rec(ix + 1, result ++ magnets(ix).chars)
    rec()
  }
  case class ApplyMagnet(chars: Seq[Char])
  object ApplyMagnet {
    implicit def fromChar(c: Char) = ApplyMagnet(c :: Nil)
    implicit def fromChars(chars: Seq[Char]) = ApplyMagnet(chars)
    implicit def fromString(chars: String) = ApplyMagnet(chars)
  }

  def from(predicate: Char ⇒ Boolean): CharPredicate = GeneralCharPredicate(predicate)

  val LowerAlpha = CharPredicate('a' to 'z')
  val UpperAlpha = CharPredicate('A' to 'Z')
  val Alpha = LowerAlpha ++ UpperAlpha
  val Digit = CharPredicate('0' to '9')
  val AlphaNum = Alpha ++ Digit
  val HexLetter = CharPredicate('a' to 'f', 'A' to 'F')
  val HexAlpha = Digit ++ HexLetter

  val WhiteSpace = CharPredicate(' ', '\t')
  val Visible = CharPredicate('\u0021' to '\u007e')
  val Printable = Visible ++ ' '

  val HttpToken = AlphaNum ++ "!#$%&\'*+-.^_`|~"

  ///////////////////////// PRIVATE ////////////////////////////

  private class ArrayBasedPredicate(private val chars: Array[Char]) extends (Char ⇒ Boolean) {
    import java.util.Arrays._
    sort(chars)
    def apply(c: Char): Boolean = binarySearch(chars, c) > 0
    def ++(other: ArrayBasedPredicate): ArrayBasedPredicate = this ++ other.chars
    def --(other: ArrayBasedPredicate): ArrayBasedPredicate = this -- other.chars
    def ++(other: Array[Char]): ArrayBasedPredicate = new ArrayBasedPredicate((this -- other).chars ++ other)
    def --(other: Array[Char]): ArrayBasedPredicate = new ArrayBasedPredicate(chars.filter(binarySearch(other, _) < 0))
  }

  private def or(a: Char ⇒ Boolean, b: Char ⇒ Boolean): CharPredicate = from(c ⇒ a(c) || b(c))
  private def andNot(a: Char ⇒ Boolean, b: Char ⇒ Boolean): CharPredicate = from(c ⇒ a(c) && !b(c))

  private type ABP = ArrayBasedPredicate // brevity alias
  private case class GeneralCharPredicate(predicate: Char ⇒ Boolean) extends CharPredicate {
    def apply(c: Char) = predicate(c)
    def ++(that: CharPredicate): CharPredicate = (this, that) match {
      case (GeneralCharPredicate(thisChars: ABP), GeneralCharPredicate(thatChars: ABP)) ⇒ from(thisChars ++ thatChars)
      case (_, GeneralCharPredicate(thatPredicate)) ⇒ or(predicate, thatPredicate)
      case _ ⇒ or(predicate, that)
    }
    def ++(chars: Seq[Char]): CharPredicate = predicate match {
      case x: ArrayBasedPredicate ⇒ from(x ++ chars.toArray)
      case _                      ⇒ or(predicate, new ArrayBasedPredicate(chars.toArray))
    }
    def --(that: CharPredicate): CharPredicate = (this, that) match {
      case (GeneralCharPredicate(thisChars: ABP), GeneralCharPredicate(thatChars: ABP)) ⇒ from(thisChars -- thatChars)
      case (_, GeneralCharPredicate(thatPredicate)) ⇒ andNot(predicate, thatPredicate)
      case _ ⇒ andNot(predicate, that)
    }
    def --(chars: Seq[Char]): CharPredicate = predicate match {
      case x: ArrayBasedPredicate ⇒ from(x -- chars.toArray)
      case _                      ⇒ andNot(predicate, new ArrayBasedPredicate(chars.toArray))
    }
  }

  // efficient handling of 7bit-ASCII chars excluding 0x00
  private case class CharMask(lowMask: Long, highMask: Long) extends CharPredicate {
    def apply(char: Char): Boolean = {
      val c = ranged(char)
      if (c < 64) (lowMask & (1L << c)) != 0L
      else (highMask & (1L << (c - 64))) != 0L
    }
    def ++(that: CharPredicate): CharPredicate = that match {
      case _ if this == Empty                  ⇒ that
      case CharMask(low, high)                 ⇒ CharMask(lowMask | low, highMask | high)
      case GeneralCharPredicate(thatPredicate) ⇒ or(this, thatPredicate)
      case _                                   ⇒ or(this, that)
    }
    def ++(chars: Seq[Char]): CharPredicate = chars.foldLeft(this: CharPredicate) {
      case (_: CharMask, c) if !maskable(c)   ⇒ if (this == Empty) from(abp(chars)) else or(this, abp(chars))
      case (CharMask(low, high), c) if c < 64 ⇒ CharMask(low | 1L << c, high)
      case (CharMask(low, high), c)           ⇒ CharMask(low, high | 1L << (c - 64))
      case (x, _)                             ⇒ x
    }
    def --(that: CharPredicate): CharPredicate = that match {
      case CharMask(low, high)                 ⇒ CharMask(lowMask & ~low, highMask & ~high)
      case GeneralCharPredicate(thatPredicate) ⇒ andNot(this, thatPredicate)
      case _                                   ⇒ andNot(this, that)
    }
    def --(chars: Seq[Char]): CharPredicate =
      if (this != Empty) chars.foldLeft(this: CharPredicate) {
        case (_: CharMask, c) if !maskable(c)   ⇒ andNot(this, abp(chars))
        case (CharMask(low, high), c) if c < 64 ⇒ CharMask(low & ~(1L << c), high)
        case (CharMask(low, high), c)           ⇒ CharMask(low, high & ~(1L << (c - 64)))
        case (x, _)                             ⇒ x
      }
      else this

    override def toString(): String = "CharMask(%016x|%016x)" format (lowMask, highMask)

    private def ranged(c: Char) = c & ((c - 128) >> 31) // branchless for `if (c < 128) c else 0`
    private def maskable(c: Char) = '\u0000' < c && c < '\u0080'
    private def abp(chars: Seq[Char]) = new ArrayBasedPredicate(chars.toArray)
  }
}
