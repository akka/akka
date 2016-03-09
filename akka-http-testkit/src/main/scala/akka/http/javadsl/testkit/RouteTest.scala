/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import scala.annotation.varargs
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.impl.util.AddFutureAwaitResult
import akka.http.impl.util.JavaMapping.Implicits.AddAsScala
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.headers.Host
import akka.http.javadsl.server.AllDirectives
import akka.http.javadsl.server.Directives
import akka.http.javadsl.server.HttpApp
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{ Route ⇒ ScalaRoute }
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.settings.RoutingSettings
import akka.stream.Materializer

/**
 * A base class to create route tests for testing libraries. An implementation needs to provide
 * code to provide and shutdown an [[akka.actor.ActorSystem]], [[akka.stream.Materializer]], and [[scala.concurrent.ExecutionContextExecutor]].
 * Also an implementation should provide instances of [[TestResponse]] to define the assertion
 * facilities of the testing library.
 *
 * See `JUnitRouteTest` for an example of a concrete implementation.
 */
abstract class RouteTest extends AllDirectives {
  implicit def system: ActorSystem
  implicit def materializer: Materializer
  implicit def executionContext: ExecutionContextExecutor = system.dispatcher

  protected def awaitDuration: FiniteDuration = 500.millis

  protected def defaultHostInfo: DefaultHostInfo = DefaultHostInfo(Host.create("example.com"), false)

  def runRoute(route: Route, request: HttpRequest): TestRouteResult =
    runRoute(route, request, defaultHostInfo)

  def runRoute(route: Route, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestRouteResult =
    runScalaRoute(route.seal(system, materializer).toScala, request, defaultHostInfo)

  def runRouteUnSealed(route: Route, request: HttpRequest): TestRouteResult =
    runRouteUnSealed(route, request, defaultHostInfo)

  def runRouteUnSealed(route: Route, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestRouteResult =
    runScalaRoute(route.toScala, request, defaultHostInfo)

  private def runScalaRoute(scalaRoute: ScalaRoute, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestRouteResult = {
    val effectiveRequest = request.asScala
      .withEffectiveUri(
        securedConnection = defaultHostInfo.isSecuredConnection(),
        defaultHostHeader = defaultHostInfo.getHost().asScala)

    val result = scalaRoute(new server.RequestContextImpl(effectiveRequest, NoLogging, RoutingSettings(system)))

    createTestRouteResult(result.awaitResult(awaitDuration))
  }

  /**
   * Wraps a list of route alternatives with testing support.
   */
  @varargs
  def testRoute(first: Route, others: Route*): TestRoute =
    new TestRoute {
      val underlying: Route = Directives.route(first +: others: _*)

      def run(request: HttpRequest): TestRouteResult = runRoute(underlying, request)
    }

  /**
   * Creates a [[TestRoute]] for the main route of an [[akka.http.javadsl.server.HttpApp]].
   */
  def testAppRoute(app: HttpApp): TestRoute = testRoute(app.createRoute())

  protected def createTestRouteResult(result: RouteResult): TestRouteResult
}
