/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package directives

import akka.http.model.HttpMethod
import akka.http.model.HttpMethods._

trait MethodDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * A route filter that rejects all non-DELETE requests.
   */
  def delete: Directive0 = MethodDirectives._delete

  /**
   * A route filter that rejects all non-GET requests.
   */
  def get: Directive0 = MethodDirectives._get

  /**
   * A route filter that rejects all non-HEAD requests.
   */
  def head: Directive0 = MethodDirectives._head

  /**
   * A route filter that rejects all non-OPTIONS requests.
   */
  def options: Directive0 = MethodDirectives._options

  /**
   * A route filter that rejects all non-PATCH requests.
   */
  def patch: Directive0 = MethodDirectives._patch

  /**
   * A route filter that rejects all non-POST requests.
   */
  def post: Directive0 = MethodDirectives._post

  /**
   * A route filter that rejects all non-PUT requests.
   */
  def put: Directive0 = MethodDirectives._put

  /**
   * Rejects all requests whose HTTP method does not match the given one.
   */
  def method(httpMethod: HttpMethod): Directive0 =
    extract(_.request.method).flatMap[Unit] {
      case `httpMethod` ⇒ pass
      case _            ⇒ reject(MethodRejection(httpMethod))
    } & cancelRejections(classOf[MethodRejection])

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
    FIXME /* // TODO: reactive after ParameterDirectives have been ported

    import ParameterDirectives._
    parameter(paramName?) flatMap {
      case Some(method) ⇒
        getForKey(method.toUpperCase) match {
          case Some(m) ⇒ mapRequest(_.copy(method = m))
          case _       ⇒ complete(StatusCodes.NotImplemented)
        }
      case _ ⇒ noop
    }*/
}

object MethodDirectives extends MethodDirectives {
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
