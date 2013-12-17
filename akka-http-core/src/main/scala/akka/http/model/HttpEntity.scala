package akka.http.model

/**
 * An HTTP entity that is either empty or consists of binary data with a ContentType to interpret the data.
 */
trait HttpEntity

object HttpEntity {
  case object Empty extends HttpEntity
  case class NonEmpty private[HttpEntity] (contentType: ContentType, data: HttpData.NonEmpty) extends HttpEntity
}
