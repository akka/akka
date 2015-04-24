/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi

import akka.http.util._
import akka.http.model.japi.JavaMapping.Implicits._

import akka.http.model.HttpResponse
import akka.http.server.{ RouteResult, RoutingSettings, Route }
import akka.http.server.japi.impl.RouteImplementation

import scala.annotation.varargs
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.server

import akka.http.model.japi.HttpRequest
import akka.stream.ActorFlowMaterializer

abstract class RouteTest {
  implicit def system: ActorSystem
  implicit def materializer: ActorFlowMaterializer
  implicit def executionContext: ExecutionContext = system.dispatcher

  protected def awaitDuration: FiniteDuration = 500.millis

  def runRoute(route: Route, request: HttpRequest): TestResponse = {
    val scalaRoute = Route.seal(RouteImplementation(route))
    val result = scalaRoute(new server.RequestContextImpl(request.asScala, NoLogging, RoutingSettings(system)))

    result.awaitResult(awaitDuration) match {
      case RouteResult.Complete(response) ⇒ createTestResponse(response)
    }
  }

  @varargs
  def testRoute(first: Route, others: Route*): TestRoute =
    new TestRoute {
      val underlying: Route = Directives.route(first, others: _*)

      def run(request: HttpRequest): TestResponse = runRoute(underlying, request)
    }

  protected def createTestResponse(response: HttpResponse): TestResponse
}
