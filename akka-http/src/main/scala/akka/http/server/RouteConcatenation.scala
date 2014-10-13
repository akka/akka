/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

trait RouteConcatenation {

  implicit def enhanceRouteWithConcatenation(route: Route) = new RouteConcatenation(route: Route)

  class RouteConcatenation(route: Route) {
    /**
     * Returns a Route that chains two Routes. If the first Route rejects the request the second route is given a
     * chance to act upon the request.
     */
    def ~(other: Route): Route = ctx ⇒
      route(ctx).recoverRejectionsWith(outerRejections ⇒
        other(ctx).recoverRejections(innerRejections ⇒
          RouteResult.rejected(outerRejections ++ innerRejections))(ctx.executionContext))(ctx.executionContext)
  }

}

object RouteConcatenation extends RouteConcatenation