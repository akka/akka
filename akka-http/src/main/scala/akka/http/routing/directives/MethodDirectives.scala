/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing
package directives

import akka.http.model.{ StatusCodes, HttpMethod }
import akka.http.model.HttpMethods._
import akka.shapeless.HNil

trait MethodDirectives {
  import BasicDirectives._
  import MiscDirectives._
  import RouteDirectives._
  import ParameterDirectives._

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

  //# method-directive
  /**
   * Rejects all requests whose HTTP method does not match the given one.
   */
  def method(httpMethod: HttpMethod): Directive0 =
    extract(_.request.method).flatMap[HNil] {
      case `httpMethod` ⇒ pass
      case _            ⇒ reject(MethodRejection(httpMethod))
    } & cancelAllRejections(ofType[MethodRejection])
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
    FIXME /*parameter(paramName?) flatMap {
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
