package akka.http.model

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
  def apply(body: String, charset: HttpCharset): HttpData = ???

  case object Empty
  trait NonEmpty
}
