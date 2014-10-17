/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.http.util.FastFuture
import FastFuture._

trait RouteConcatenation {

  implicit def enhanceRouteWithConcatenation(route: Route) = new RouteConcatenation(route: Route)

  class RouteConcatenation(route: Route) {
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

object RouteConcatenation extends RouteConcatenation