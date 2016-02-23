/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._

trait RouteConcatenation {

  implicit def enhanceRouteWithConcatenation(route: Route): RouteConcatenation.RouteWithConcatenation =
    new RouteConcatenation.RouteWithConcatenation(route: Route)
}

object RouteConcatenation extends RouteConcatenation {

  class RouteWithConcatenation(route: Route) {
    /**
     * Returns a Route that chains two Routes. If the first Route rejects the request the second route is given a
     * chance to act upon the request.
     */
    def ~(other: Route): Route = { ctx ⇒
      import ctx.executionContext
      route(ctx).fast.flatMap {
        case x: RouteResult.Complete ⇒ FastFuture.successful(x)
        case RouteResult.Rejected(outerRejections) ⇒
          other(ctx).fast.map {
            case x: RouteResult.Complete               ⇒ x
            case RouteResult.Rejected(innerRejections) ⇒ RouteResult.Rejected(outerRejections ++ innerRejections)
          }
      }
    }
  }
}