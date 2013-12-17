package akka.http

import scala.annotation.tailrec

package object util {
  implicit class StringWithAsciiBytes(val underlying: String) extends AnyVal {
    /** Returns the ASCII encoded bytes of this string. Truncates characters to 8-bit byte value */
    def getAsciiBytes = {
      @tailrec def bytes(array: Array[Byte] = new Array[Byte](underlying.length), ix: Int = 0): Array[Byte] =
        if (ix < array.length) {
          val ch = underlying.charAt(ix)
          array(ix) = ch.toByte
          bytes(array, ix + 1)
        } else array
      bytes()
    }
  }
}
