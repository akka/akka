/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
   * by tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26
   *
   * In particular the algorithm defined by tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-6
   * is implemented by this directive.
   */
  @varargs
  def conditional(entityTag: EntityTag, lastModified: DateTime, innerRoutes: Route*): Route =
    RouteStructure.Conditional(entityTag, lastModified, innerRoutes.toVector)
}
