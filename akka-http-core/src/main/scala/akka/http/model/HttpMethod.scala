package akka.http.model

/**
 * The method of an HTTP request.
 */
trait HttpMethod {
  def isIdempotent: Boolean
}

object HttpMethods {
  def GET: HttpMethod = ???
}
