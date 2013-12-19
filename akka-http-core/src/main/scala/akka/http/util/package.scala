/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.annotation.tailrec
import java.nio.charset.Charset

package object util {
  val UTF8 = Charset.forName("UTF8")

  implicit class RichString(val underlying: String) extends AnyVal {
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

    /**
     * Returns Some(String) if the underlying string is non-emtpy, None otherwise
     */
    def toOption: Option[String] = if ((underlying eq null) || underlying.isEmpty) None else Some(underlying)
  }
}
