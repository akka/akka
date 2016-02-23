/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.javadsl.model.DateTime
import akka.http.javadsl.model.headers.EntityTag
import akka.http.impl.server.RouteStructure

import scala.annotation.varargs

abstract class CacheConditionDirectives extends BasicDirectives {
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
   */
  @varargs
  def conditional(entityTag: EntityTag, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.Conditional(entityTag = Some(entityTag))(innerRoute, moreInnerRoutes.toList)

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
   */
  @varargs
  def conditional(lastModified: DateTime, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.Conditional(lastModified = Some(lastModified))(innerRoute, moreInnerRoutes.toList)

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
   */
  @varargs
  def conditional(entityTag: EntityTag, lastModified: DateTime, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.Conditional(Some(entityTag), Some(lastModified))(innerRoute, moreInnerRoutes.toList)
}
