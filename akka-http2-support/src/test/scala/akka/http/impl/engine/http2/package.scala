/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
}
