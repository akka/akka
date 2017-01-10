/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.engine

import akka.util.ByteString

package object http2 {
  implicit class RichString(val str: String) extends AnyVal {
    def parseHexByteString: ByteString =
      ByteString(
        str.replaceAll("\\s", "").trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      )
  }
  implicit class HexInterpolatorString(val sc: StringContext) extends AnyVal {
    def hex(args: Any*): ByteString = {
      val strings = sc.parts.iterator
      val expressions = args.iterator
      val buf = new StringBuffer(strings.next)
      while (strings.hasNext) {
        buf append expressions.next
        buf append strings.next
      }
      buf.toString.parseHexByteString
    }
  }
}
