/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.hello

import akka.actor._
import akka.http._
import akka.config.Supervision._

class Boot {
  val factory =
    SupervisorFactory(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, 100),
        Supervise(Actor.actorOf[RootEndpoint], Permanent) ::
        Supervise(Actor.actorOf[HelloEndpoint], Permanent) :: Nil))

  factory.newInstance.start()
}
