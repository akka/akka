/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import scala.annotation.tailrec
import akka.util.ByteString

/**
 * Straight-forward Boyer-Moore string search implementation.
 */
private class BoyerMoore(needle: Array[Byte]) {
  require(needle.length > 0, "needle must be non-empty")

  private[this] val nl1 = needle.length - 1

  private[this] val charTable: Array[Int] = {
    val table = Array.fill(256)(needle.length)
    @tailrec def rec(i: Int): Unit =
      if (i < nl1) {
        table(needle(i) & 0xff) = nl1 - i
        rec(i + 1)
      }
    rec(0)
    table
  }

  private[this] val offsetTable: Array[Int] = {
    val table = new Array[Int](needle.length)

    @tailrec def isPrefix(i: Int, j: Int): Boolean =
      i == needle.length || needle(i) == needle(j) && isPrefix(i + 1, j + 1)
    @tailrec def loop1(i: Int, lastPrefixPosition: Int): Unit =
      if (i >= 0) {
        val nextLastPrefixPosition = if (isPrefix(i + 1, 0)) i + 1 else lastPrefixPosition
        table(nl1 - i) = nextLastPrefixPosition - i + nl1
        loop1(i - 1, nextLastPrefixPosition)
      }
    loop1(nl1, needle.length)

    @tailrec def suffixLength(i: Int, j: Int, result: Int): Int =
      if (i >= 0 && needle(i) == needle(j)) suffixLength(i - 1, j - 1, result + 1) else result
    @tailrec def loop2(i: Int): Unit =
      if (i < nl1) {
        val sl = suffixLength(i, nl1, 0)
        table(sl) = nl1 - i + sl
        loop2(i + 1)
      }
    loop2(0)
    table
  }

  /**
   * Returns the index of the next occurrence of `needle` in `haystack` that is >= `offset`.
   * If none is found a `NotEnoughDataException` is thrown.
   */
  def nextIndex(haystack: ByteString, offset: Int): Int = {
    @tailrec def rec(i: Int, j: Int): Int = {
      val byte = byteAt(haystack, i)
      if (needle(j) == byte) {
        if (j == 0) i // found
        else rec(i - 1, j - 1)
      } else rec(i + math.max(offsetTable(nl1 - j), charTable(byte & 0xff)), nl1)
    }
    rec(offset + nl1, nl1)
  }
}
