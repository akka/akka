/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.settings.{ ParserSettings, RoutingSettings }
import akka.stream.{ ActorMaterializer, ActorMaterializerHelper, Materializer }

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
   * "Seals" a route by wrapping it with default exception handling and rejection conversion.
   *
   * A sealed route has these properties:
   *  - The result of the route will always be a complete response, i.e. the result of the future is a
   *    ``Success(RouteResult.Complete(response))``, never a failed future and never a rejected route. These
   *    will be already be handled using the implicitly given [[RejectionHandler]] and [[ExceptionHandler]] (or
   *    the default handlers if none are given or can be found implicitly).
   *  - Consequently, no route alternatives will be tried that were combined with this route
   *    using the ``~`` on routes or the [[Directive.|]] operator on directives.
   */
  def seal(route: Route)(implicit
    routingSettings: RoutingSettings,
                         parserSettings:   ParserSettings   = null,
                         rejectionHandler: RejectionHandler = RejectionHandler.default,
                         exceptionHandler: ExceptionHandler = null): Route = {
    import directives.ExecutionDirectives._
    // optimized as this is the root handler for all akka-http applications
    (handleExceptions(ExceptionHandler.seal(exceptionHandler)) & handleRejections(rejectionHandler.seal))
      .tapply(_ ⇒ route) // execute above directives eagerly, avoiding useless laziness of Directive.addByNameNullaryApply
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
      val effectiveParserSettings = if (parserSettings ne null) parserSettings else ParserSettings(ActorMaterializerHelper.downcast(materializer).system)

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
