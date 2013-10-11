/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import RouterDocSpec.MyActor
import akka.testkit.AkkaSpec
import akka.routing.RoundRobinRouter
import akka.actor.{ ActorRef, Props, Actor }

object RouterDocSpec {
  class MyActor extends Actor {
    def receive = {
      case _ ⇒
    }
  }
}

class RouterDocSpec extends AkkaSpec("""
router {}
workers {}
""") {

  import RouterDocSpec._

  //#dispatchers
  val router: ActorRef = system.actorOf(Props[MyActor]
    // “head” will run on "router" dispatcher
    .withRouter(RoundRobinRouter(5, routerDispatcher = "router"))
    // MyActor workers will run on "workers" dispatcher
    .withDispatcher("workers"))
  //#dispatchers

}
