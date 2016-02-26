/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.testkit.ScalatestRouteTest
import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

abstract class RoutingSpec extends WordSpec with Matchers
  with Directives with ScalatestRouteTest
  with CompileOnlySpec
