package akka.http.model

/**
 * A data structure that either wraps ByteStrings, references data in files, or a compound of both
 * of the above.
 */
trait HttpData

object HttpData {
  def apply(bytes: Array[Byte]): HttpData = ???
  def apply(body: String, charset: HttpCharset): HttpData = ???

  case object Empty
  trait NonEmpty
}
