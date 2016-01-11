/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.testkit

import scala.annotation.varargs
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.stream.Materializer
import akka.http.scaladsl.server
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.headers.Host
import akka.http.javadsl.server.{ HttpApp, AllDirectives, Route, Directives }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.impl.server.RouteImplementation
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.{ RouteResult, RoutingSettings, Route ⇒ ScalaRoute }
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.impl.util._

/**
 * A base class to create route tests for testing libraries. An implementation needs to provide
 * code to provide and shutdown an [[ActorSystem]], [[Materializer]], and [[ExecutionContext]].
 * Also an implementation should provide instances of [[TestResponse]] to define the assertion
 * facilities of the testing library.
 *
 * See `JUnitRouteTest` for an example of a concrete implementation.
 */
abstract class RouteTest extends AllDirectives {
  implicit def system: ActorSystem
  implicit def materializer: Materializer
  implicit def executionContext: ExecutionContext = system.dispatcher

  protected def awaitDuration: FiniteDuration = 500.millis

  protected def defaultHostInfo: DefaultHostInfo = DefaultHostInfo(Host.create("example.com"), false)

  def runRoute(route: Route, request: HttpRequest): TestResponse =
    runRoute(route, request, defaultHostInfo)

  def runRoute(route: Route, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestResponse =
    runScalaRoute(ScalaRoute.seal(RouteImplementation(route)), request, defaultHostInfo)

  def runRouteUnSealed(route: Route, request: HttpRequest): TestResponse =
    runRouteUnSealed(route, request, defaultHostInfo)

  def runRouteUnSealed(route: Route, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestResponse =
    runScalaRoute(RouteImplementation(route), request, defaultHostInfo)

  private def runScalaRoute(scalaRoute: ScalaRoute, request: HttpRequest, defaultHostInfo: DefaultHostInfo): TestResponse = {
    val effectiveRequest = request.asScala
      .withEffectiveUri(
        securedConnection = defaultHostInfo.isSecuredConnection(),
        defaultHostHeader = defaultHostInfo.getHost().asScala)

    val result = scalaRoute(new server.RequestContextImpl(effectiveRequest, NoLogging, RoutingSettings(system)))

    result.awaitResult(awaitDuration) match {
      case RouteResult.Complete(response) ⇒ createTestResponse(response)
      case RouteResult.Rejected(ex)       ⇒ throw new AssertionError("got unexpected rejection: " + ex)
    }
  }

  /**
   * Wraps a list of route alternatives with testing support.
   */
  @varargs
  def testRoute(first: Route, others: Route*): TestRoute =
    new TestRoute {
      val underlying: Route = Directives.route(first, others: _*)

      def run(request: HttpRequest): TestResponse = runRoute(underlying, request)
    }

  /**
   * Creates a [[TestRoute]] for the main route of an [[HttpApp]].
   */
  def testAppRoute(app: HttpApp): TestRoute = testRoute(app.createRoute())

  protected def createTestResponse(response: HttpResponse): TestResponse
}
