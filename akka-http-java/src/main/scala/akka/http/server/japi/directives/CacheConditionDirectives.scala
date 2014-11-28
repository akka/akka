/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package directives

import akka.http.model.japi.DateTime
import akka.http.model.japi.headers.EntityTag
import akka.http.server.japi.impl.RouteStructure

import scala.annotation.varargs

trait CacheConditionDirectives {
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
