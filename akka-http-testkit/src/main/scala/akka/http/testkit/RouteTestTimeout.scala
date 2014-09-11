/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit

import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.testkit._

case class RouteTestTimeout(duration: FiniteDuration)

object RouteTestTimeout {
  implicit def default(implicit system: ActorSystem) = RouteTestTimeout(1.second dilated)
}
