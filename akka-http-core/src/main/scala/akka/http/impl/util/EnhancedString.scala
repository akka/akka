/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.util.Locale
import scala.annotation.tailrec
import scala.collection.immutable

/**
 * INTERNAL API
 */
private[http] class EnhancedString(val underlying: String) extends AnyVal {

  /**
   * Splits the underlying string into the segments that are delimited by the given character.
   * The delimiter itself is never a part of any segment. If the string does not contain the
   * delimiter the result is a List containing only the underlying string.
   * Note that this implementation differs from the original String.split(...) method in that
   * leading and trailing delimiters are NOT ignored, i.e. they trigger the inclusion of an
   * empty leading or trailing empty string (respectively).
   */
  def fastSplit(delimiter: Char): immutable.LinearSeq[String] = {
    @tailrec def split(end: Int = underlying.length, elements: List[String] = Nil): List[String] = {
      val ix = underlying.lastIndexOf(delimiter, end - 1)
      if (ix < 0)
        underlying.substring(0, end) :: elements
      else
        split(ix, underlying.substring(ix + 1, end) :: elements)
    }
    split()
  }

  /**
   * Lazily splits the underlying string into the segments that are delimited by the given character.
   * Only the segments that are actually accessed are computed.
   * The delimiter itself is never a part of any segment. If the string does not contain the
   * delimiter the result is a single-element stream containing only the underlying string.
   * Note that this implementation differs from the original String.split(...) method in that
   * leading and trailing delimiters are NOT ignored, i.e. they trigger the inclusion of an
   * empty leading or trailing empty string (respectively).
   */
  def lazySplit(delimiter: Char): Stream[String] = {
    def split(start: Int = 0): Stream[String] = {
      val ix = underlying.indexOf(delimiter, start)
      if (ix < 0)
        Stream.cons(underlying.substring(start), Stream.Empty)
      else
        Stream.cons(underlying.substring(start, ix), split(ix + 1))
    }
    split()
  }

  /**
   * Returns Some(String) if the underlying string is non-empty, None otherwise
   */
  def toOption: Option[String] =
    if ((underlying eq null) || underlying.isEmpty) None else Some(underlying)

  /**
   * If the underlying string is null the method returns the empty string, otherwise the underlying string.
   */
  def nullAsEmpty: String =
    if (underlying eq null) "" else underlying

  /**
   * Returns the ASCII encoded bytes of this string. Truncates characters to 8-bit byte value.
   */
  def asciiBytes: Array[Byte] = {
    val array = new Array[Byte](underlying.length)
    getAsciiBytes(array, 0)
    array
  }

  /**
   * Copies the ASCII encoded bytes of this string into the given byte array starting at the `offset` index.
   * Truncates characters to 8-bit byte value.
   * If the array does not have enough space for the whole string only the portion that fits is copied.
   */
  def getAsciiBytes(array: Array[Byte], offset: Int): Unit = {
    @tailrec def rec(ix: Int): Unit =
      if (ix < array.length) {
        array(ix) = underlying.charAt(ix - offset).asInstanceOf[Byte]
        rec(ix + 1)
      }
    rec(offset)
  }

  /**
   * Tests two string for value equality in a way that defends against timing attacks.
   * Simple equality testing will stop at the end of a matching prefix thereby leaking information
   * about the length of the matching prefix which can be exploited for per-byte progressive brute-forcing.
   *
   * @note This function leaks information about the length of each string as well as
   *       whether the two string have the same length.
   * @see [[http://codahale.com/a-lesson-in-timing-attacks/]]
   * @see [[http://rdist.root.org/2009/05/28/timing-attack-in-google-keyczar-library/]]
   * @see [[http://emerose.com/timing-attacks-explained]]
   */
  def secure_==(other: String): Boolean = asciiBytes secure_== other.asciiBytes

  /**
   * Determines whether the underlying String starts with the given character.
   */
  def startsWith(c: Char): Boolean = underlying.nonEmpty && underlying.charAt(0) == c

  /**
   * Determines whether the underlying String ends with the given character.
   */
  def endsWith(c: Char): Boolean = underlying.nonEmpty && underlying.charAt(underlying.length - 1) == c

  /** Strips margin and fixes the newline sequence to the given one preventing dependencies on the build platform */
  def stripMarginWithNewline(newline: String) = underlying.stripMargin.replace("\r\n", "\n").replace("\n", newline)

  /**
   * Provides a default toLowerCase that doesn't suffer from the dreaded turkish-i problem.
   * See http://bugs.java.com/view_bug.do?bug_id=6208680
   */
  def toRootLowerCase: String = underlying.toLowerCase(Locale.ROOT)
}
