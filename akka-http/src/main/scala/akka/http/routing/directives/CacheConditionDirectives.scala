/*
 * Copyright © 2011-2014 the spray project <http://spray.io>
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

import akka.http.model._
import akka.http.util.DateTime
import headers._
import HttpMethods._
import StatusCodes._
import EntityTag._

trait CacheConditionDirectives {
  import BasicDirectives._

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26
   *
   * In particular the algorithm defined by tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   */
  def conditional(eTag: EntityTag, lastModified: DateTime): Directive0 =
    mapInnerRoute { route ⇒
      ctx ⇒ {
        def ctxWithResponseHeaders =
          ctx.withHttpResponseMapped(_.withDefaultHeaders(List(ETag(eTag), `Last-Modified`(lastModified))))

        import ctx.request._

        // TODO: also handle Cache-Control and Vary
        def complete304() = ctxWithResponseHeaders.complete(HttpResponse(NotModified))
        def complete412() = ctx.complete(PreconditionFailed)
        def runInnerRouteWithRangeHeaderFilteredOut() =
          route(ctxWithResponseHeaders.withRequestMapped(_.mapHeaders(_.filterNot(_.isInstanceOf[Range]))))

        def isGetOrHead = method == HEAD || method == GET
        def unmodified(ifModifiedSince: DateTime) =
          lastModified <= ifModifiedSince && ifModifiedSince.clicks < System.currentTimeMillis()

        step1()

        def step1() =
          header[`If-Match`] match {
            case Some(`If-Match`(im)) ⇒ if (matchesRange(eTag, im, weak = false)) step3() else complete412()
            case None                 ⇒ step2()
          }
        def step2() =
          header[`If-Unmodified-Since`] match {
            case Some(`If-Unmodified-Since`(ius)) if !unmodified(ius) ⇒ complete412()
            case _ ⇒ step3()
          }
        def step3() =
          header[`If-None-Match`] match {
            case Some(`If-None-Match`(inm)) ⇒
              if (!matchesRange(eTag, inm, weak = true)) step5()
              else if (isGetOrHead) complete304() else complete412()
            case None ⇒ step4()
          }
        def step4() =
          if (isGetOrHead) {
            header[`If-Modified-Since`] match {
              case Some(`If-Modified-Since`(ims)) if unmodified(ims) ⇒ complete304()
              case _ ⇒ step5()
            }
          } else step5()
        def step5() =
          if (method == GET && header[Range].isDefined)
            header[`If-Range`] match {
              case Some(`If-Range`(Left(tag))) if !matches(eTag, tag, weak = false) ⇒ runInnerRouteWithRangeHeaderFilteredOut()
              case Some(`If-Range`(Right(ims))) if !unmodified(ims) ⇒ runInnerRouteWithRangeHeaderFilteredOut()
              case _ ⇒ step6()
            }
          else step6()
        def step6(): RouteResult = route(ctxWithResponseHeaders)

        step1()
      }
    }
}

object CacheConditionDirectives extends CacheConditionDirectives
