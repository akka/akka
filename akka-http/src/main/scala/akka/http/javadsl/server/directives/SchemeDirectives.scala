/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import akka.http.impl.server.RouteStructure.SchemeFilter
import akka.http.javadsl.server.Route

import scala.annotation.varargs

abstract class SchemeDirectives extends RangeDirectives {
  /**
   * Rejects all requests whose Uri scheme does not match the given one.
   */
  @varargs
  def scheme(scheme: String, innerRoute: Route, moreInnerRoutes: Route*): Route = SchemeFilter(scheme)(innerRoute, moreInnerRoutes.toList)
}
