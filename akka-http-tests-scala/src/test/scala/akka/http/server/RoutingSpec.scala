/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import org.scalatest.{ WordSpec, Suite, Matchers }
import akka.http.model.HttpResponse
import akka.http.testkit.ScalatestRouteTest

trait GenericRoutingSpec extends Matchers with Directives with ScalatestRouteTest { this: Suite ⇒
  val Ok = HttpResponse()
  val completeOk = complete(Ok)

  def echoComplete[T]: T ⇒ Route = { x ⇒ complete(x.toString) }
  def echoComplete2[T, U]: (T, U) ⇒ Route = { (x, y) ⇒ complete(s"$x $y") }
}

abstract class RoutingSpec extends WordSpec with GenericRoutingSpec
