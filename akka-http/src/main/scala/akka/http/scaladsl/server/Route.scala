/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.settings.{ RoutingSettings, ParserSettings }
import akka.stream.{ ActorMaterializer, Materializer }

import scala.concurrent.{ ExecutionContextExecutor, Future }
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
  def seal(route: Route)(implicit
    routingSettings: RoutingSettings,
                         parserSettings:   ParserSettings   = null,
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
   * This conversion is also implicitly available through [[RouteResult#route2HandlerFlow]].
   */
  def handlerFlow(route: Route)(implicit
    routingSettings: RoutingSettings,
                                parserSettings:   ParserSettings,
                                materializer:     Materializer,
                                routingLog:       RoutingLog,
                                executionContext: ExecutionContextExecutor = null,
                                rejectionHandler: RejectionHandler         = RejectionHandler.default,
                                exceptionHandler: ExceptionHandler         = null): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow[HttpRequest].mapAsync(1)(asyncHandler(route))

  /**
   * Turns a `Route` into an async handler function.
   */
  def asyncHandler(route: Route)(implicit
    routingSettings: RoutingSettings,
                                 parserSettings:   ParserSettings,
                                 materializer:     Materializer,
                                 routingLog:       RoutingLog,
                                 executionContext: ExecutionContextExecutor = null,
                                 rejectionHandler: RejectionHandler         = RejectionHandler.default,
                                 exceptionHandler: ExceptionHandler         = null): HttpRequest ⇒ Future[HttpResponse] = {
    val effectiveEC = if (executionContext ne null) executionContext else materializer.executionContext

    {
      implicit val executionContext = effectiveEC // overrides parameter
      val effectiveParserSettings = if (parserSettings ne null) parserSettings else ParserSettings(ActorMaterializer.downcast(materializer).system)

      val sealedRoute = seal(route)
      request ⇒
        sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), routingSettings, effectiveParserSettings)).fast
          .map {
            case RouteResult.Complete(response) ⇒ response
            case RouteResult.Rejected(rejected) ⇒ throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
          }
    }
  }
}
