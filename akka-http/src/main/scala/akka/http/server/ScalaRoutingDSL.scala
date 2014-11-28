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

  sealed trait Applicator[R] {
    def withRoute(route: Route): R
    def withSyncHandler(handler: HttpRequest ⇒ HttpResponse): R
    def withAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse]): R
  }

  def handleConnections(serverSource: Http.ServerSource)(implicit fm: FlowMaterializer,
                                                         setupProvider: RoutingSetupProvider): Applicator[Unit] = {
    new Applicator[Unit] {
      def withRoute(route: Route): Unit =
        run(routeRunner(route, _))

      def withSyncHandler(handler: HttpRequest ⇒ HttpResponse): Unit =
        withAsyncHandler(request ⇒ FastFuture.successful(handler(request)))

      def withAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse]): Unit =
        run(_ ⇒ handler)

      private def run(f: RoutingSetup ⇒ HttpRequest ⇒ Future[HttpResponse]): Unit =
        serverSource.source.foreach {
          case connection @ Http.IncomingConnection(remoteAddress, flow) ⇒
            val setup = setupProvider(connection)
            setup.routingLog.log.debug("Accepted new connection from " + remoteAddress)
            val runner = f(setup)
            flow.join(Flow[HttpRequest].mapAsync(request ⇒ runner(request))).run()(fm)
        }
    }
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