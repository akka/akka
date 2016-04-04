/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.DateTime
import headers._
import HttpMethods._
import StatusCodes._
import EntityTag._

/**
 * @groupname cachecondition Cache condition directives
 * @groupprio cachecondition 20
 */
trait CacheConditionDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   *
   * @group cachecondition
   */
  def conditional(eTag: EntityTag): Directive0 = conditional(Some(eTag), None)

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   *
   * @group cachecondition
   */
  def conditional(lastModified: DateTime): Directive0 = conditional(None, Some(lastModified))

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   *
   * @group cachecondition
   */
  def conditional(eTag: EntityTag, lastModified: DateTime): Directive0 = conditional(Some(eTag), Some(lastModified))

  /**
   * Wraps its inner route with support for Conditional Requests as defined
   * by http://tools.ietf.org/html/rfc7232
   *
   * In particular the algorithm defined by http://tools.ietf.org/html/rfc7232#section-6
   * is implemented by this directive.
   *
   * Note: if you want to combine this directive with `withRangeSupport(...)` you need to put
   * it on the *outside* of the `withRangeSupport(...)` directive, i.e. `withRangeSupport(...)`
   * must be on a deeper level in your route structure in order to function correctly.
   *
   * @group cachecondition
   */
  def conditional(eTag: Option[EntityTag], lastModified: Option[DateTime]): Directive0 = {
    def addResponseHeaders: Directive0 =
      mapResponse(_.withDefaultHeaders(eTag.map(ETag(_)).toList ++ lastModified.map(`Last-Modified`(_)).toList))

    // TODO: also handle Cache-Control and Vary
    def complete304(): Route = addResponseHeaders(complete(HttpResponse(NotModified)))
    def complete412(): Route = _.complete(PreconditionFailed)

    extractRequest.flatMap { request ⇒
      import request._
      mapInnerRoute { route ⇒
        def innerRouteWithRangeHeaderFilteredOut: Route =
          (mapRequest(_.mapHeaders(_.filterNot(_.isInstanceOf[Range]))) &
            addResponseHeaders)(route)

        def isGetOrHead = method == HEAD || method == GET
        def unmodified(ifModifiedSince: DateTime) =
          lastModified.get <= ifModifiedSince && ifModifiedSince.clicks < System.currentTimeMillis()

        def step1(): Route =
          header[`If-Match`] match {
            case Some(`If-Match`(im)) if eTag.isDefined ⇒
              if (matchesRange(eTag.get, im, weakComparison = false)) step3() else complete412()
            case None ⇒ step2()
          }
        def step2(): Route =
          header[`If-Unmodified-Since`] match {
            case Some(`If-Unmodified-Since`(ius)) if lastModified.isDefined && !unmodified(ius) ⇒ complete412()
            case _ ⇒ step3()
          }
        def step3(): Route =
          header[`If-None-Match`] match {
            case Some(`If-None-Match`(inm)) if eTag.isDefined ⇒
              if (!matchesRange(eTag.get, inm, weakComparison = true)) step5()
              else if (isGetOrHead) complete304() else complete412()
            case None ⇒ step4()
          }
        def step4(): Route =
          if (isGetOrHead) {
            header[`If-Modified-Since`] match {
              case Some(`If-Modified-Since`(ims)) if lastModified.isDefined && unmodified(ims) ⇒ complete304()
              case _ ⇒ step5()
            }
          } else step5()
        def step5(): Route =
          if (method == GET && header[Range].isDefined)
            header[`If-Range`] match {
              case Some(`If-Range`(Left(tag))) if eTag.isDefined && !matches(eTag.get, tag, weakComparison = false) ⇒
                innerRouteWithRangeHeaderFilteredOut
              case Some(`If-Range`(Right(ims))) if lastModified.isDefined && !unmodified(ims) ⇒
                innerRouteWithRangeHeaderFilteredOut
              case _ ⇒ step6()
            }
          else step6()
        def step6(): Route = addResponseHeaders(route)

        step1()
      }
    }
  }
}

object CacheConditionDirectives extends CacheConditionDirectives
