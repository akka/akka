/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.routing

import akka.testkit._
import akka.actor._
import akka.routing.RoundRobinPool
import akka.actor.OneForOneStrategy

object ClusterRouterSupervisorSpec {

  class KillableActor(testActor: ActorRef) extends Actor {

    def receive = {
      case "go away" ⇒
        throw new IllegalArgumentException("Goodbye then!")
    }

  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterRouterSupervisorSpec extends AkkaSpec("""
  akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
  akka.remote.netty.tcp.port = 0
""") {

  import ClusterRouterSupervisorSpec._

  "Cluster aware routers" must {

    "use provided supervisor strategy" in {
      val router = system.actorOf(
        ClusterRouterPool(RoundRobinPool(nrOfInstances = 1, supervisorStrategy =
          OneForOneStrategy(loggingEnabled = false) {
            case _ ⇒
              testActor ! "supervised"
              SupervisorStrategy.Stop
          }), ClusterRouterPoolSettings(
          totalInstances = 1,
          maxInstancesPerNode = 1,
          allowLocalRoutees = true,
          useRole = None)).
          props(Props(classOf[KillableActor], testActor)), name = "therouter")

      router ! "go away"
      expectMsg("supervised")
    }

  }

}
