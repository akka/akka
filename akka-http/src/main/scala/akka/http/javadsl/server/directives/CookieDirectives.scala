/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import akka.http.impl.server.RouteStructure
import akka.http.javadsl.model.headers.HttpCookie
import akka.http.javadsl.server.Route

import scala.annotation.varargs

abstract class CookieDirectives extends CodingDirectives {
  /**
   * Adds a Set-Cookie header with the given cookies to all responses of its inner route.
   */
  @varargs def setCookie(cookie: HttpCookie, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.SetCookie(cookie)(innerRoute, moreInnerRoutes.toList)
}
