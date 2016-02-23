/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.lang.{ Boolean ⇒ JBoolean }

import akka.http.impl.server.RouteStructure.{ DynamicDirectiveRoute2, Validated, DynamicDirectiveRoute1 }

import scala.annotation.varargs
import akka.japi.function.{ Function2, Function }

abstract class MiscDirectives extends MethodDirectives {
  /**
   * Returns a Route which checks the given condition on the request context before running its inner Route.
   * If the condition fails the  route is rejected with a [[akka.http.scaladsl.server.ValidationRejection]].
   */
  @varargs
  def validate(check: Function[RequestContext, JBoolean], errorMsg: String, innerRoute: Route, moreInnerRoutes: Route*): Route =
    validate(RequestVals.requestContext, check, errorMsg, innerRoute, moreInnerRoutes: _*)

  /**
   * Returns a Route which checks the given condition before running its inner Route. If the condition fails the
   * route is rejected with a [[akka.http.scaladsl.server.ValidationRejection]].
   */
  @varargs
  def validate[T](value: RequestVal[T], check: Function[T, JBoolean], errorMsg: String, innerRoute: Route, moreInnerRoutes: Route*): Route =
    new DynamicDirectiveRoute1[T](value)(innerRoute, moreInnerRoutes.toList) {
      def createDirective(t1: T): Directive = Directives.custom(Validated(check.apply(t1), errorMsg))
    }

  /**
   * Returns a Route which checks the given condition before running its inner Route. If the condition fails the
   * route is rejected with a [[akka.http.scaladsl.server.ValidationRejection]].
   */
  @varargs
  def validate[T1, T2](value1: RequestVal[T1],
                       value2: RequestVal[T2],
                       check: Function2[T1, T2, JBoolean],
                       errorMsg: String,
                       innerRoute: Route, moreInnerRoutes: Route*): Route =
    new DynamicDirectiveRoute2[T1, T2](value1, value2)(innerRoute, moreInnerRoutes.toList) {
      def createDirective(t1: T1, t2: T2): Directive = Directives.custom(Validated(check.apply(t1, t2), errorMsg))
    }
}
