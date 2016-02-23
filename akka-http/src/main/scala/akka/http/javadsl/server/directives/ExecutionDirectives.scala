/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure

import scala.annotation.varargs
import scala.reflect.ClassTag

abstract class ExecutionDirectives extends CookieDirectives {
  /**
   * Handles exceptions in the inner routes using the specified handler.
   */
  @varargs
  def handleExceptions(handler: ExceptionHandler, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.HandleExceptions(handler)(innerRoute, moreInnerRoutes.toList)

  /**
   * Handles rejections in the inner routes using the specified handler.
   */
  @varargs
  def handleRejections(handler: RejectionHandler, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.HandleRejections(handler)(innerRoute, moreInnerRoutes.toList)

  /**
   * Handles rejections of the given type in the inner routes using the specified handler.
   */
  @varargs
  def handleRejections[T](tClass: Class[T], handler: Handler1[T], innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.HandleRejections(new RejectionHandler {
      implicit def tTag: ClassTag[T] = ClassTag(tClass)
      override def handleCustomRejection(ctx: RequestContext, rejection: CustomRejection): RouteResult =
        rejection match {
          case t: T ⇒ handler.apply(ctx, t)
          case _    ⇒ passRejection()
        }
    })(innerRoute, moreInnerRoutes.toList)
}
