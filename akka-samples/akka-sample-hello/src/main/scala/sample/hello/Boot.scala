/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.hello

import akka.actor._
import akka.http._

class Boot {
  val supervisor = Supervisor(OneForOneStrategy(List(classOf[Exception]), 3, 100))
  Actor.actorOf(Props[RootEndpoint].withSupervisor(supervisor))
  Actor.actorOf(Props[HelloEndpoint].withSupervisor(supervisor))
}
