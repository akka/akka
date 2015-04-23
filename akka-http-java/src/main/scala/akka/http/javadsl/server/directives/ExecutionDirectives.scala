/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives

import akka.http.impl.server.RouteStructure
import akka.http.javadsl.server.{ Route, ExceptionHandler }

import scala.annotation.varargs

abstract class ExecutionDirectives extends CodingDirectives {
  /**
   * Handles exceptions in the inner routes using the specified handler.
   */
  @varargs
  def handleExceptions(handler: ExceptionHandler, innerRoutes: Route*): Route =
    RouteStructure.HandleExceptions(handler, innerRoutes.toVector)
}
