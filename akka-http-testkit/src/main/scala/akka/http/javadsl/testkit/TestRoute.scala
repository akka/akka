/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.server.Route

/**
 * A wrapped route that has a `run` method to run a request through the underlying route to create
 * a [[TestResponse]].
 *
 * A TestRoute is created by deriving a test class from the concrete RouteTest implementation for your
 * testing framework (like [[JUnitRouteTest]] for JUnit) and then using its `testRoute` method to wrap
 * a route with testing support.
 */
trait TestRoute {
  def underlying: Route
  def run(request: HttpRequest): TestRouteResult
}