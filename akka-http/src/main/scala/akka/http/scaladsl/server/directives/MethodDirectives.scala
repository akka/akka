/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.{ StatusCodes, HttpMethod }
import akka.http.scaladsl.model.HttpMethods._

trait MethodDirectives {
  import BasicDirectives._
  import RouteDirectives._
  import ParameterDirectives._
  import MethodDirectives._

  /**
   * Rejects all non-DELETE requests.
   */
  def delete: Directive0 = _delete

  /**
   * Rejects all non-GET requests.
   */
  def get: Directive0 = _get

  /**
   * Rejects all non-HEAD requests.
   */
  def head: Directive0 = _head

  /**
   * Rejects all non-OPTIONS requests.
   */
  def options: Directive0 = _options

  /**
   * Rejects all non-PATCH requests.
   */
  def patch: Directive0 = _patch

  /**
   * Rejects all non-POST requests.
   */
  def post: Directive0 = _post

  /**
   * Rejects all non-PUT requests.
   */
  def put: Directive0 = _put

  /**
   * Extracts the request method.
   */
  def extractMethod: Directive1[HttpMethod] = _extractMethod

  //#method
  /**
   * Rejects all requests whose HTTP method does not match the given one.
   */
  def method(httpMethod: HttpMethod): Directive0 =
    extractMethod.flatMap[Unit] {
      case `httpMethod` ⇒ pass
      case _            ⇒ reject(MethodRejection(httpMethod))
    } & cancelRejections(classOf[MethodRejection])
  //#

  /**
   * Changes the HTTP method of the request to the value of the specified query string parameter. If the query string
   * parameter is not specified this directive has no effect. If the query string is specified as something that is not
   * a HTTP method, then this directive completes the request with a `501 Not Implemented` response.
   *
   * This directive is useful for:
   *  - Use in combination with JSONP (JSONP only supports GET)
   *  - Supporting older browsers that lack support for certain HTTP methods. E.g. IE8 does not support PATCH
   */
  def overrideMethodWithParameter(paramName: String): Directive0 =
    parameter(paramName?) flatMap {
      case Some(method) ⇒
        getForKey(method.toUpperCase) match {
          case Some(m) ⇒ mapRequest(_.copy(method = m))
          case _       ⇒ complete(StatusCodes.NotImplemented)
        }
      case None ⇒ pass
    }
}

object MethodDirectives extends MethodDirectives {
  private val _extractMethod: Directive1[HttpMethod] =
    BasicDirectives.extract(_.request.method)

  // format: OFF
  private val _delete : Directive0 = method(DELETE)
  private val _get    : Directive0 = method(GET)
  private val _head   : Directive0 = method(HEAD)
  private val _options: Directive0 = method(OPTIONS)
  private val _patch  : Directive0 = method(PATCH)
  private val _post   : Directive0 = method(POST)
  private val _put    : Directive0 = method(PUT)
  // format: ON
}
