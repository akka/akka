/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.stream.Materializer

import scala.concurrent.{ ExecutionContext, Future }
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
  def seal(route: Route)(implicit routingSettings: RoutingSettings,
                         rejectionHandler: RejectionHandler = RejectionHandler.default,
                         exceptionHandler: ExceptionHandler = null): Route = {
    import directives.ExecutionDirectives._
    handleExceptions(ExceptionHandler.seal(exceptionHandler)) {
      handleRejections(rejectionHandler.seal) {
        route
      }
    }
  }

  /**
   * Turns a `Route` into a server flow.
   *
   * This conversion is also implicitly available through [[RouteResult.route2HandlerFlow]].
   */
  def handlerFlow(route: Route)(implicit routingSettings: RoutingSettings,
                                materializer: Materializer,
                                routingLog: RoutingLog,
                                executionContext: ExecutionContext = null,
                                rejectionHandler: RejectionHandler = RejectionHandler.default,
                                exceptionHandler: ExceptionHandler = null): Flow[HttpRequest, HttpResponse, Unit] =
    Flow[HttpRequest].mapAsync(1)(asyncHandler(route))

  /**
   * Turns a `Route` into an async handler function.
   */
  def asyncHandler(route: Route)(implicit routingSettings: RoutingSettings,
                                 materializer: Materializer,
                                 routingLog: RoutingLog,
                                 executionContext: ExecutionContext = null,
                                 rejectionHandler: RejectionHandler = RejectionHandler.default,
                                 exceptionHandler: ExceptionHandler = null): HttpRequest ⇒ Future[HttpResponse] = {
    val effectiveEC = if (executionContext ne null) executionContext else materializer.executionContext

    {
      implicit val executionContext = effectiveEC // overrides parameter

      val sealedRoute = seal(route)
      request ⇒
        sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), routingSettings)).fast
          .map {
            case RouteResult.Complete(response) ⇒ response
            case RouteResult.Rejected(rejected) ⇒ throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
          }
    }
  }
}
