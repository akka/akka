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

import akka.http.marshalling.ToResponseMarshallable
import akka.http.model._
import StatusCodes._

trait RouteDirectives {

  /**
   * Rejects the request with an empty set of rejections.
   */
  def reject: StandardRoute = RouteDirectives._reject

  /**
   * Rejects the request with the given rejections.
   */
  def reject(rejections: Rejection*): StandardRoute = new StandardRoute {
    def apply(ctx: RequestContext): RouteResult = ctx.reject(rejections: _*)
  }

  /**
   * Completes the request with redirection response of the given type to the given URI.
   */
  def redirect(uri: Uri, redirectionType: Redirection): StandardRoute = new StandardRoute {
    def apply(ctx: RequestContext): RouteResult = ctx.redirect(uri, redirectionType)
  }

  /**
   * Completes the request using the given arguments.
   */
  def complete: (⇒ ToResponseMarshallable) ⇒ StandardRoute = marshallable ⇒ new StandardRoute {
    def apply(ctx: RequestContext): RouteResult = ctx.complete(marshallable)
  }

  /**
   * Bubbles the given error up the response chain, where it is dealt with by the closest `handleExceptions`
   * directive and its ExceptionHandler.
   */
  def failWith(error: Throwable): StandardRoute = new StandardRoute {
    def apply(ctx: RequestContext): RouteResult = ctx.failWith(error)
  }
}

object RouteDirectives extends RouteDirectives {
  private val _reject: StandardRoute = new StandardRoute {
    def apply(ctx: RequestContext): RouteResult = ctx.reject()
  }
}
