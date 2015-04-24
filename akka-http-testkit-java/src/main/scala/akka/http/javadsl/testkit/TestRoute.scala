/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.testkit

import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.server.Route

trait TestRoute {
  def underlying: Route
  def run(request: HttpRequest): TestResponse
}