/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.routing

import RouterDocSpec.MyActor
import akka.actor.{ Props, Actor }
import akka.testkit.AkkaSpec
import akka.routing.RoundRobinRouter

object RouterDocSpec {
  class MyActor extends Actor {
    def receive = {
      case _ ⇒
    }
  }
}

class RouterDocSpec extends AkkaSpec {

  import RouterDocSpec._

  //#dispatchers
  val router = system.actorOf(Props[MyActor]
    .withRouter(RoundRobinRouter(5, routerDispatcher = "router")) // “head” will run on "router" dispatcher
    .withDispatcher("workers")) // MyActor workers will run on "workers" dispatcher
  //#dispatchers

}