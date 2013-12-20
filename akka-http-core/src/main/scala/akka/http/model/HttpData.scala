/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.util.ByteString
import java.io.File
import java.nio.charset.Charset

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
  // just a placeholder
  case class Simple(bytes: ByteString) extends HttpData {
    def toByteArray: Array[Byte] = bytes.toArray[Byte]
    def copyToArray(xs: Array[Byte], sourceOffset: Long, targetOffset: Int, span: Int): Unit = {
      require(targetOffset == 0)
      bytes.copyToArray(xs, sourceOffset.toInt, span)
    }
    def nonEmpty: Boolean = bytes.nonEmpty
    def longLength: Long = bytes.length
  }

  def apply(bytes: Array[Byte]): HttpData = Simple(ByteString(bytes))
  def apply(bytes: ByteString): HttpData = Simple(bytes)
  def apply(body: String, charset: Charset): HttpData = Simple(ByteString(body.getBytes(charset)))
  def apply(file: File): HttpData = ???
  def Empty: HttpData = Simple(ByteString.empty)
}
