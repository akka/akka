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

import scala.collection.immutable

import akka.shapeless.HNil
import akka.http.model._

trait RespondWithDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Overrides the given response status on all HTTP responses of its inner Route.
   */
  def respondWithStatus(responseStatus: StatusCode): Directive0 =
    mapHttpResponse(_.copy(status = responseStatus))

  /**
   * Unconditionally adds the given response header to all HTTP responses of its inner Route.
   */
  def respondWithHeader(responseHeader: HttpHeader): Directive0 =
    mapHttpResponseHeaders(responseHeader +: _)

  /**
   * Adds the given response header to all HTTP responses of its inner Route,
   * if the response from the inner Route doesn't already contain a header with the same name.
   */
  def respondWithSingletonHeader(responseHeader: HttpHeader): Directive0 =
    mapHttpResponseHeaders { headers ⇒
      if (headers.exists(_.is(responseHeader.lowercaseName))) headers
      else responseHeader +: headers
    }

  /**
   * Unconditionally adds the given response headers to all HTTP responses of its inner Route.
   */
  def respondWithHeaders(responseHeaders: HttpHeader*): Directive0 =
    respondWithHeaders(responseHeaders.toList)

  /**
   * Unconditionally adds the given response headers to all HTTP responses of its inner Route.
   */
  def respondWithHeaders(responseHeaders: immutable.Seq[HttpHeader]): Directive0 =
    mapHttpResponseHeaders(responseHeaders ++ _)

  /**
   * Adds the given response headers to all HTTP responses of its inner Route,
   * if a header already exists it is not added again.
   */
  def respondWithSingletonHeaders(responseHeaders: HttpHeader*): Directive0 =
    respondWithSingletonHeaders(responseHeaders.toList)

  /* Adds the given response headers to all HTTP responses of its inner Route,
   * if a header already exists it is not added again.
   */
  def respondWithSingletonHeaders(responseHeaders: List[HttpHeader]): Directive0 =
    mapHttpResponseHeaders {
      responseHeaders.foldLeft(_) {
        case (headers, h) ⇒ if (headers.exists(_.is(h.lowercaseName))) headers else h +: headers
      }
    }

  /**
   * Overrides the media-type of the response returned by its inner route with the given one.
   * If the given media-type is not accepted by the client the request is rejected with an
   * UnacceptedResponseContentTypeRejection.
   * Note, that this directive removes a potentially existing 'Accept' header from the request,
   * in order to "disable" content negotiation in a potentially running Marshaller in its inner route.
   * Also note that this directive does *not* change the response entity buffer content in any way,
   * it merely overrides the media-type component of the entities Content-Type.
   */
  def respondWithMediaType(mediaType: MediaType): Directive0 = {
    val rejection = UnacceptedResponseContentTypeRejection(ContentType(mediaType) :: Nil)
    extract(_.request.isMediaTypeAccepted(mediaType)).flatMap[HNil] { if (_) pass else reject(rejection) } &
      mapRequestContext(_.withContentNegotiationDisabled) &
      mapHttpResponseEntity(entity ⇒ entity.withContentType(entity.contentType.withMediaType(mediaType))) &
      MiscDirectives.cancelRejection(rejection)
  }
}

object RespondWithDirectives extends RespondWithDirectives