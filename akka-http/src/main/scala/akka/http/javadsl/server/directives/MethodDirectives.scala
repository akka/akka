/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import akka.http.javadsl.model.{ HttpMethods, HttpMethod }
import akka.http.javadsl.server.Route
import akka.http.impl.server.RouteStructure

import scala.annotation.varargs

abstract class MethodDirectives extends HostDirectives {
  /** Handles the inner routes if the incoming request is a GET request, rejects the request otherwise */
  @varargs
  def get(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.GET, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a POST request, rejects the request otherwise */
  @varargs
  def post(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.POST, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a PUT request, rejects the request otherwise */
  @varargs
  def put(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.PUT, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a DELETE request, rejects the request otherwise */
  @varargs
  def delete(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.DELETE, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a HEAD request, rejects the request otherwise */
  @varargs
  def head(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.HEAD, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a OPTIONS request, rejects the request otherwise */
  @varargs
  def options(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.OPTIONS, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a PATCH request, rejects the request otherwise */
  @varargs
  def patch(innerRoute: Route, moreInnerRoutes: Route*): Route = method(HttpMethods.PATCH, innerRoute, moreInnerRoutes: _*)

  /** Handles the inner routes if the incoming request is a request with the given method, rejects the request otherwise */
  @varargs
  def method(method: HttpMethod, innerRoute: Route, moreInnerRoutes: Route*): Route = RouteStructure.MethodFilter(method)(innerRoute, moreInnerRoutes.toList)
}