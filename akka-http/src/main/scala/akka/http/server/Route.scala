/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.concurrent.Future
import akka.stream.scaladsl.Flow
import akka.http.model.{ HttpRequest, HttpResponse }
import akka.http.util.FastFuture._

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
    val sealedExceptionHandler =
      if (exceptionHandler.isDefault) exceptionHandler
      else exceptionHandler orElse ExceptionHandler.default(settings)
    val sealedRejectionHandler =
      if (rejectionHandler.isDefault) rejectionHandler
      else rejectionHandler orElse RejectionHandler.default
    handleExceptions(sealedExceptionHandler) {
      handleRejections(sealedRejectionHandler) {
        route
      }
    }
  }

  /**
   * Turns a `Route` into an server flow.
   */
  def handlerFlow(route: Route)(implicit setup: RoutingSetup): Flow[HttpRequest, HttpResponse, Unit] =
    Flow[HttpRequest].mapAsync(asyncHandler(route))

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