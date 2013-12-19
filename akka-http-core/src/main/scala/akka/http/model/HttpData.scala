/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.util.ByteString
import java.io.File

/**
 * A data structure that either wraps ByteStrings, references data in files, or a compound of both
 * of the above.
 */
trait HttpData {
  def toByteArray: Array[Byte]
  def copyToArray(xs: Array[Byte], sourceOffset: Long = 0, targetOffset: Int = 0, span: Int = math.min(longLength, Int.MaxValue).toInt): Unit
  def nonEmpty: Boolean
  def longLength: Long
}

object HttpData {
  def apply(bytes: Array[Byte]): HttpData = ???
  def apply(bytes: ByteString): HttpData = ???
  def apply(body: String, charset: HttpCharset): HttpData = ???
  def apply(file: File): HttpData = ???

  def Empty: HttpData = ???
}
