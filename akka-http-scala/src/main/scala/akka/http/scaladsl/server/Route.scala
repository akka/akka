/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import scala.concurrent.Future
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.util.FastFuture._

object Route {

  /**
   * Helper for constructing a Route from a function literal.
   */
  def apply(f: Route): Route = f

  /**
   * "Seals" a route by wrapping it with exception handling and rejection conversion.
   */
  def seal(route: Route)(implicit setup: RoutingSetup): Route = {
    import directives.ExecutionDirectives._
    import setup._
    handleExceptions(exceptionHandler.seal(settings)) {
      handleRejections(rejectionHandler.seal) {
        route
      }
    }
  }

  /**
   * Turns a `Route` into an server flow.
   */
  def handlerFlow(route: Route)(implicit setup: RoutingSetup): Flow[HttpRequest, HttpResponse, Unit] =
    Flow[HttpRequest].mapAsync(1, asyncHandler(route))

  /**
   * Turns a `Route` into an async handler function.
   */
  def asyncHandler(route: Route)(implicit setup: RoutingSetup): HttpRequest ⇒ Future[HttpResponse] = {
    import setup._
    val sealedRoute = seal(route)
    request ⇒
      sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), setup.settings)).fast.map {
        case RouteResult.Complete(response) ⇒ response
        case RouteResult.Rejected(rejected) ⇒ throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
      }
  }
}
