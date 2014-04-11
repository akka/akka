/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.annotation.tailrec

class PimpedString(val underlying: String) extends AnyVal {

  /**
   * Splits the underlying string into the segments that are delimited by the given character.
   * The delimiter itself is never a part of any segment. If the string does not contain the
   * delimiter the result is a List containing only the underlying string.
   * Note that this implementation differs from the original String.split(...) method in that
   * leading and trailing delimiters are NOT ignored, i.e. they trigger the inclusion of an
   * empty leading or trailing empty string (respectively).
   */
  def fastSplit(delimiter: Char): List[String] = {
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
    // based on an implemented by Jed Wesley-Smith
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
   * Returns Some(String) if the underlying string is non-emtpy, None otherwise
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
  def getAsciiBytes = {
    @tailrec def bytes(array: Array[Byte] = new Array[Byte](underlying.length), ix: Int = 0): Array[Byte] =
      if (ix < array.length) {
        array(ix) = underlying.charAt(ix).asInstanceOf[Byte]
        bytes(array, ix + 1)
      } else array
    bytes()
  }

  /**
   * Tests two strings for value equality avoiding timing attacks.
   * Note that this function still leaks information about the length of each string as well as
   * whether the two strings have the same length.
   */
  def secure_==(other: String): Boolean = getAsciiBytes secure_== other.getAsciiBytes

  /**
   * Determines whether the underlying String starts with the given character.
   */
  def startsWith(c: Char) = underlying.nonEmpty && underlying.charAt(0) == c

  /**
   * Determines whether the underlying String ends with the given character.
   */
  def endsWith(c: Char) = underlying.nonEmpty && underlying.charAt(underlying.length - 1) == c

  /** Strips margin and fixes the newline sequence to the given one preventing dependencies on the build platform */
  def stripMarginWithNewline(newline: String) = underlying.stripMargin.replace("\r\n", "\n").replace("\n", newline)
}
