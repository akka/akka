package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import scala.collection.immutable

/**
 * @groupname response Response directives
 * @groupprio response 190
 */
trait RespondWithDirectives {
  import BasicDirectives._

  /**
   * Unconditionally adds the given response header to all HTTP responses of its inner Route.
   *
   * @group response
   */
  def respondWithHeader(responseHeader: HttpHeader): Directive0 = respondWithHeaders(responseHeader)

  /**
   * Adds the given response header to all HTTP responses of its inner Route,
   * if the response from the inner Route doesn't already contain a header with the same name.
   *
   * @group response
   */
  def respondWithDefaultHeader(responseHeader: HttpHeader): Directive0 = respondWithDefaultHeaders(responseHeader)

  /**
   * Unconditionally adds the given response headers to all HTTP responses of its inner Route.
   *
   * @group response
   */
  def respondWithHeaders(responseHeaders: HttpHeader*): Directive0 =
    respondWithHeaders(responseHeaders.toList)

  /**
   * Unconditionally adds the given response headers to all HTTP responses of its inner Route.
   *
   * @group response
   */
  def respondWithHeaders(responseHeaders: immutable.Seq[HttpHeader]): Directive0 =
    mapResponseHeaders(responseHeaders ++ _)

  /**
   * Adds the given response headers to all HTTP responses of its inner Route,
   * if a header already exists it is not added again.
   *
   * @group response
   */
  def respondWithDefaultHeaders(responseHeaders: HttpHeader*): Directive0 =
    respondWithDefaultHeaders(responseHeaders.toList)

  /**
   * Adds the given response headers to all HTTP responses of its inner Route,
   * if a header already exists it is not added again.
   *
   * @group response
   */
  def respondWithDefaultHeaders(responseHeaders: immutable.Seq[HttpHeader]): Directive0 =
    mapResponse(_.withDefaultHeaders(responseHeaders))
}

object RespondWithDirectives extends RespondWithDirectives
