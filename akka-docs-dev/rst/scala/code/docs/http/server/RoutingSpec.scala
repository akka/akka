/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.server

import akka.http.server.Directives
import akka.http.testkit.ScalatestRouteTest
import org.scalatest.{ Matchers, WordSpec }

abstract class RoutingSpec extends WordSpec with Matchers with Directives with ScalatestRouteTest
