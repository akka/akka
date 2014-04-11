/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.annotation.tailrec

class PimpedByteArray(val underlying: Array[Byte]) extends AnyVal {

  /**
   * Tests two byte arrays for value equality avoiding timing attacks.
   *
   * @note This function leaks information about the length of each byte array as well as
   *       whether the two byte arrays have the same length.
   * @see [[http://codahale.com/a-lesson-in-timing-attacks/]]
   * @see [[http://rdist.root.org/2009/05/28/timing-attack-in-google-keyczar-library/]]
   * @see [[http://emerose.com/timing-attacks-explained]]
   */
  def secure_==(other: Array[Byte]): Boolean = {
    @tailrec def xor(ix: Int = 0, result: Int = 0): Int =
      if (ix < underlying.length) xor(ix + 1, result | (underlying(ix) ^ other(ix))) else result

    other.length == underlying.length && xor() == 0
  }
}
