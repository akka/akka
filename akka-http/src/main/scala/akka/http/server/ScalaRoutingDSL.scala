/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.stream.scaladsl2.{ ForeachSink, FlowFrom, FlowMaterializer }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import akka.http.util.Deferrable
import akka.http.model.{ HttpRequest, HttpResponse }
import akka.http.Http

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
    def withHandler(handler: HttpRequest ⇒ Deferrable[HttpResponse]): R
  }

  def handleConnections(bindingFuture: Future[Http.ServerBinding])(implicit ec: ExecutionContext, fm: FlowMaterializer,
                                                                   setupProvider: RoutingSetupProvider): Applicator[Future[Unit]] =
    new Applicator[Future[Unit]] {
      def withRoute(route: Route) = afterBinding(_ withRoute route)
      def withSyncHandler(handler: HttpRequest ⇒ HttpResponse) = afterBinding(_ withSyncHandler handler)
      def withAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse]) = afterBinding(_ withAsyncHandler handler)
      def withHandler(handler: HttpRequest ⇒ Deferrable[HttpResponse]) = afterBinding(_ withHandler handler)

      def afterBinding(f: Applicator[Unit] ⇒ Unit): Future[Unit] =
        bindingFuture.map(binding ⇒ f(handleConnections(binding)))
    }

  def handleConnections(binding: Http.ServerBinding)(implicit fm: FlowMaterializer,
                                                     setupProvider: RoutingSetupProvider): Applicator[Unit] = {
    new Applicator[Unit] {
      def withRoute(route: Route): Unit =
        run(routeRunner(route, _))

      def withSyncHandler(handler: HttpRequest ⇒ HttpResponse): Unit =
        withHandler(request ⇒ Deferrable(handler(request)))

      def withAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse]): Unit =
        withHandler(request ⇒ Deferrable(handler(request)))

      def withHandler(handler: HttpRequest ⇒ Deferrable[HttpResponse]): Unit =
        run(_ ⇒ handler)

      private def run(f: RoutingSetup ⇒ HttpRequest ⇒ Deferrable[HttpResponse]): Unit =
        FlowFrom(binding.connectionStream).runWithSink(ForeachSink {
          case connection @ Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            val setup = setupProvider(connection)
            setup.routingLog.log.debug("Accepted new connection from " + remoteAddress)
            val runner = f(setup)
            FlowFrom(requestProducer)
              .mapFuture(request ⇒ runner(request).toFuture)
              .publishTo(responseConsumer)(setup.flowMaterializer)
        })
    }
  }

  def routeRunner(route: Route, setup: RoutingSetup): HttpRequest ⇒ Deferrable[HttpResponse] = {
    import setup._
    val sealedRoute = sealRoute(route)(setup)
    request ⇒
      sealedRoute(new RequestContextImpl(request, routingLog.requestLog(request))) map {
        case RouteResult.Complete(response) ⇒ response
        case RouteResult.Rejected(rejected) ⇒ throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
        case RouteResult.Failure(error)     ⇒ throw new IllegalStateException(s"Unhandled error '$error', unsealed ExceptionHandler?!")
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