/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives

import akka.http.javadsl.model.{ HttpMethods, HttpMethod }
import akka.http.javadsl.server.Route
import akka.http.impl.server.RouteStructure

import scala.annotation.varargs

abstract class MethodDirectives extends FileAndResourceDirectives {
  /** Handles the inner routes if the incoming request is a GET request, rejects the request otherwise */
  @varargs
  def get(innerRoutes: Route*): Route = method(HttpMethods.GET, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a POST request, rejects the request otherwise */
  @varargs
  def post(innerRoutes: Route*): Route = method(HttpMethods.POST, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a PUT request, rejects the request otherwise */
  @varargs
  def put(innerRoutes: Route*): Route = method(HttpMethods.PUT, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a DELETE request, rejects the request otherwise */
  @varargs
  def delete(innerRoutes: Route*): Route = method(HttpMethods.DELETE, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a HEAD request, rejects the request otherwise */
  @varargs
  def head(innerRoutes: Route*): Route = method(HttpMethods.HEAD, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a OPTIONS request, rejects the request otherwise */
  @varargs
  def options(innerRoutes: Route*): Route = method(HttpMethods.OPTIONS, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a PATCH request, rejects the request otherwise */
  @varargs
  def patch(innerRoutes: Route*): Route = method(HttpMethods.PATCH, innerRoutes: _*)

  /** Handles the inner routes if the incoming request is a request with the given method, rejects the request otherwise */
  @varargs
  def method(method: HttpMethod, innerRoutes: Route*): Route = RouteStructure.MethodFilter(method, innerRoutes.toVector)
}