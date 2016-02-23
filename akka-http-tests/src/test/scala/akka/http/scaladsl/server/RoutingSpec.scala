/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import org.scalatest.{ WordSpec, Suite, Matchers }
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.testkit.ScalatestRouteTest

trait GenericRoutingSpec extends Matchers with Directives with ScalatestRouteTest { this: Suite ⇒
  val Ok = HttpResponse()
  val completeOk = complete(Ok)

  def echoComplete[T]: T ⇒ Route = { x ⇒ complete(x.toString) }
  def echoComplete2[T, U]: (T, U) ⇒ Route = { (x, y) ⇒ complete(s"$x $y") }
}

abstract class RoutingSpec extends WordSpec with GenericRoutingSpec
