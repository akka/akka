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

import scala.reflect.{ classTag, ClassTag }
import akka.shapeless._
import akka.http.model._
import akka.parboiled2.CharPredicate
import headers._
import MediaTypes._

trait MiscDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Returns a Directive which checks the given condition before passing on the [[spray.routing.RequestContext]] to
   * its inner Route. If the condition fails the route is rejected with a [[spray.routing.ValidationRejection]].
   */
  def validate(check: ⇒ Boolean, errorMsg: String): Directive0 =
    new Directive0 {
      def happly(f: HNil ⇒ Route) = if (check) f(HNil) else reject(ValidationRejection(errorMsg))
    }

  /**
   * Directive extracting the IP of the client from either the X-Forwarded-For, Remote-Address or X-Real-IP header
   * (in that order of priority).
   */
  def clientIP: Directive1[RemoteAddress] = MiscDirectives._clientIP

  /**
   * Wraps the inner Route with JSONP support. If a query parameter with the given name is present in the request and
   * the inner Route returns content with content-type `application/json` the response content is wrapped with a call
   * to a Javascript function having the name of query parameters value. The name of this function is validated to
   * prevent XSS vulnerabilities. Only alphanumeric, underscore (_), dollar ($) and dot (.) characters are allowed.
   * Additionally the content-type is changed from `application/json` to `application/javascript` in these cases.
   */
  def jsonpWithParameter(parameterName: String): Directive0 = FIXME

  /**
   * Adds a TransformationRejection cancelling all rejections for which the given filter function returns true
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelAllRejections(cancelFilter: Rejection ⇒ Boolean): Directive0 =
    mapRejections(_ :+ TransformationRejection(_.filterNot(cancelFilter)))

  /**
   * Adds a TransformationRejection cancelling all rejections equal to the given one
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejection(rejection: Rejection): Directive0 =
    cancelAllRejections(_ == rejection)

  def ofType[T <: Rejection: ClassTag]: Rejection ⇒ Boolean = {
    val erasure = classTag[T].runtimeClass
    erasure.isInstance(_)
  }

  def ofTypes(classes: Class[_]*): Rejection ⇒ Boolean = { rejection ⇒
    classes.exists(_.isInstance(rejection))
  }

  /**
   * Rejects the request if its entity is not empty.
   */
  def requestEntityEmpty: Directive0 = MiscDirectives._requestEntityEmpty

  /**
   * Rejects empty requests with a RequestEntityExpectedRejection.
   * Non-empty requests are passed on unchanged to the inner route.
   */
  def requestEntityPresent: Directive0 = MiscDirectives._requestEntityPresent

  /**
   * Transforms the unmatchedPath of the RequestContext using the given function.
   */
  def rewriteUnmatchedPath(f: Uri.Path ⇒ Uri.Path): Directive0 =
    mapRequestContext(_.withUnmatchedPathMapped(f))

  /**
   * Extracts the unmatched path from the RequestContext.
   */
  def unmatchedPath: Directive1[Uri.Path] = MiscDirectives._unmatchedPath

  /**
   * Converts responses with an empty entity into (empty) rejections.
   * This way you can, for example, have the marshalling of a ''None'' option be treated as if the request could
   * not be matched.
   */
  def rejectEmptyResponse: Directive0 = MiscDirectives._rejectEmptyResponse

  /**
   * Extracts the complete request.
   */
  def requestInstance: Directive1[HttpRequest] = MiscDirectives._requestInstance

  /**
   * Extracts the complete request URI.
   */
  def requestUri: Directive1[Uri] = MiscDirectives._requestUri
}

object MiscDirectives extends MiscDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._
  import CharPredicate._

  private val validJsonpChars = AlphaNum ++ '.' ++ '_' ++ '$'

  private val _clientIP: Directive1[RemoteAddress] =
    headerValuePF { case `X-Forwarded-For`(Seq(address, _*)) ⇒ address } |
      headerValuePF { case `Remote-Address`(address) ⇒ address } |
      headerValuePF { case h if h.is("x-real-ip") ⇒ RemoteAddress(h.value) }

  private val _requestEntityEmpty: Directive0 = FIXME
  //extract(_.request.entity.isEmpty).flatMap(if (_) pass else reject)

  private val _requestEntityPresent: Directive0 = FIXME
  //extract(_.request.entity.isEmpty).flatMap(if (_) reject else pass)

  private val _unmatchedPath: Directive1[Uri.Path] =
    extract(_.unmatchedPath)

  private val _rejectEmptyResponse: Directive0 = FIXME
  /*mapRouteResponse {
      case HttpMessagePartWrapper(HttpResponse(_, HttpEntity.Empty, _, _), _) ⇒ Rejected(Nil)
      case x ⇒ x
    }*/

  private val _requestInstance: Directive1[HttpRequest] =
    extract(_.request)

  private val _requestUri: Directive1[Uri] =
    extract(_.request.uri)
}
