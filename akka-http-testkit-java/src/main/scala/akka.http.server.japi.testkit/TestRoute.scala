/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi

import akka.http.model.japi.HttpRequest

trait TestRoute {
  def underlying: Route
  def run(request: HttpRequest): TestResponse
}