/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.concurrent.{ ExecutionContext, Future }
import akka.stream.scaladsl._
import akka.stream.FlowMaterializer
import akka.http.util.FastFuture
import akka.http.model.{ HttpRequest, HttpResponse }
import akka.http.Http
import FastFuture._

/**
 * The main entry point into the Scala routing DSL.
 *
 * `import ScalaRoutingDSL._` to bring everything required into scope.
 */
trait ScalaRoutingDSL extends Directives {

  /**
   * Handles all connections from the given binding at maximum rate.
   */
  def handleConnections(httpServerBinding: Http.ServerBinding)(implicit fm: FlowMaterializer,
                                                               setupProvider: RoutingSetupProvider): Applicator =
    handleConnections(httpServerBinding.connections)

  /**
   * Handles all connections from the given source at maximum rate.
   */
  def handleConnections(connections: Source[Http.IncomingConnection])(implicit fm: FlowMaterializer,
                                                                      setupProvider: RoutingSetupProvider): Applicator =
    new Applicator {
      def withRoute(route: Route) =
        run(routeRunner(route, _))

      def withSyncHandler(handler: HttpRequest ⇒ HttpResponse) =
        withAsyncHandler(request ⇒ FastFuture.successful(handler(request)))

      def withAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse]) =
        run(_ ⇒ handler)

      def run(f: RoutingSetup ⇒ HttpRequest ⇒ Future[HttpResponse]): MaterializedMap = {
        val sink = ForeachSink[Http.IncomingConnection] { connection ⇒
          val setup = setupProvider(connection)
          setup.routingLog.log.debug("Accepted new connection from " + connection.remoteAddress)
          val runner = f(setup)
          connection.handleWith(Flow[HttpRequest] mapAsync runner)
        }
        connections.to(sink).run()
      }
    }

  sealed trait Applicator {
    def withRoute(route: Route): MaterializedMap
    def withSyncHandler(handler: HttpRequest ⇒ HttpResponse): MaterializedMap
    def withAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse]): MaterializedMap
  }

  def routeRunner(route: Route, setup: RoutingSetup): HttpRequest ⇒ Future[HttpResponse] = {
    import setup._
    val sealedRoute = sealRoute(route)(setup)
    request ⇒
      sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request), setup.settings)).fast.map {
        case RouteResult.Complete(response) ⇒ response
        case RouteResult.Rejected(rejected) ⇒ throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
      }
  }

  /**
   * "Seals" a route by wrapping it with exception handling and rejection conversion.
   */
  def sealRoute(route: Route)(implicit setup: RoutingSetup): Route = {
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
}

object ScalaRoutingDSL extends ScalaRoutingDSL