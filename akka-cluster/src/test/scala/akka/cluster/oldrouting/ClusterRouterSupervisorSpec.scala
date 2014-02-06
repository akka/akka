/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.oldrouting

import akka.testkit._
import akka.actor._
import akka.routing.RoundRobinRouter
import akka.actor.OneForOneStrategy
import akka.cluster.routing._

object ClusterRouterSupervisorSpec {

  class KillableActor(testActor: ActorRef) extends Actor {

    def receive = {
      case "go away" ⇒
        throw new IllegalArgumentException("Goodbye then!")
    }

  }

}

class ClusterRouterSupervisorSpec extends AkkaSpec("""
  akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
  akka.remote.netty.tcp.port = 0
""") {

  import ClusterRouterSupervisorSpec._

  "Cluster aware routers" must {

    "use provided supervisor strategy" in {
      val router = system.actorOf(Props(classOf[KillableActor], testActor).withRouter(
        ClusterRouterConfig(RoundRobinRouter(supervisorStrategy = OneForOneStrategy() {
          case _ ⇒
            testActor ! "supervised"
            SupervisorStrategy.Stop
        }), ClusterRouterSettings(
          totalInstances = 1,
          maxInstancesPerNode = 1,
          allowLocalRoutees = true,
          useRole = None))), name = "therouter")

      router ! "go away"
      expectMsg("supervised")
    }

  }

}
