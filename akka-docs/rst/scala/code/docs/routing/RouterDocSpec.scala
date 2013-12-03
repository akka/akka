/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import scala.concurrent.duration._
import akka.testkit._
import akka.actor.{ ActorRef, Props, Actor }
import akka.actor.Terminated
import akka.routing.FromConfig
import akka.routing.RoundRobinPool
import akka.routing.RandomPool
import akka.routing.RoundRobinGroup
import akka.routing.SmallestMailboxPool
import akka.routing.BroadcastPool
import akka.routing.BroadcastGroup
import akka.routing.ConsistentHashingGroup
import akka.routing.ConsistentHashingPool
import akka.routing.DefaultResizer
import akka.routing.ScatterGatherFirstCompletedGroup
import akka.routing.RandomGroup
import akka.routing.ScatterGatherFirstCompletedPool

object RouterDocSpec {

  val config = """
#//#config-round-robin-pool
akka.actor.deployment {
  /parent/router1 {
    router = round-robin-pool
    nr-of-instances = 5
  }
}
#//#config-round-robin-pool

#//#config-round-robin-group
akka.actor.deployment {
  /parent/router3 {
    router = round-robin-group
    routees.paths = ["/user/workers/w1", "/user/workers/w2", "/user/workers/w3"]
  }
}
#//#config-round-robin-group

#//#config-random-pool
akka.actor.deployment {
  /parent/router5 {
    router = random-pool
    nr-of-instances = 5
  }
}
#//#config-random-pool
    
#//#config-random-group
akka.actor.deployment {
  /parent/router7 {
    router = random-group
    routees.paths = ["/user/workers/w1", "/user/workers/w2", "/user/workers/w3"]
  }
}
#//#config-random-group
    
#//#config-smallest-mailbox-pool
akka.actor.deployment {
  /parent/router9 {
    router = smallest-mailbox-pool
    nr-of-instances = 5
  }
}
#//#config-smallest-mailbox-pool
    
#//#config-broadcast-pool
akka.actor.deployment {
  /parent/router11 {
    router = broadcast-pool
    nr-of-instances = 5
  }
}
#//#config-broadcast-pool
    
#//#config-broadcast-group
akka.actor.deployment {
  /parent/router13 {
    router = broadcast-group
    routees.paths = ["/user/workers/w1", "/user/workers/w2", "/user/workers/w3"]
  }
}
#//#config-broadcast-group

#//#config-scatter-gather-pool
akka.actor.deployment {
  /parent/router15 {
    router = scatter-gather-pool
    nr-of-instances = 5
    within = 10 seconds
  }
}
#//#config-scatter-gather-pool
    
#//#config-scatter-gather-group
akka.actor.deployment {
  /parent/router17 {
    router = scatter-gather-group
    routees.paths = ["/user/workers/w1", "/user/workers/w2", "/user/workers/w3"]
    within = 10 seconds
  }
}
#//#config-scatter-gather-group
    
#//#config-consistent-hashing-pool
akka.actor.deployment {
  /parent/router19 {
    router = consistent-hashing-pool
    nr-of-instances = 5
    virtual-nodes-factor = 10
  }
}
#//#config-consistent-hashing-pool
    
#//#config-consistent-hashing-group
akka.actor.deployment {
  /parent/router21 {
    router = consistent-hashing-group
    routees.paths = ["/user/workers/w1", "/user/workers/w2", "/user/workers/w3"]
    virtual-nodes-factor = 10
  }
}
#//#config-consistent-hashing-group

#//#config-resize-pool
akka.actor.deployment {
  /parent/router23 {
    router = round-robin-pool
    resizer {
      lower-bound = 2
      upper-bound = 15
      messages-per-resize = 100
    }
  }
}
#//#config-resize-pool

#//#config-pool-dispatcher
akka.actor.deployment {
  /poolWithDispatcher {
    router = random-pool
    nr-of-instances = 5
    pool-dispatcher {
      type = BalancingDispatcher
    }
  }
}
#//#config-pool-dispatcher
    
router-dispatcher {}
"""

  case class Work(payload: String)

  //#router-in-actor
  import akka.routing.ActorRefRoutee
  import akka.routing.Router
  import akka.routing.RoundRobinRoutingLogic

  class Master extends Actor {
    var router = {
      val routees = Vector.fill(5) {
        val r = context.actorOf(Props[Worker])
        context watch r
        ActorRefRoutee(r)
      }
      Router(RoundRobinRoutingLogic(), routees)
    }

    def receive = {
      case w: Work =>
        router.route(w, sender)
      case Terminated(a) =>
        router = router.removeRoutee(a)
        val r = context.actorOf(Props[Worker])
        context watch r
        router = router.addRoutee(r)
    }
  }
  //#router-in-actor

  class Worker extends Actor {
    def receive = {
      case _ =>
    }
  }

  //#create-worker-actors
  class Workers extends Actor {
    context.actorOf(Props[Worker], name = "w1")
    context.actorOf(Props[Worker], name = "w2")
    context.actorOf(Props[Worker], name = "w3")
    // ...
    //#create-worker-actors

    def receive = {
      case _ =>
    }
  }

  class Parent extends Actor {

    //#paths
    val paths = List("/user/workers/w1", "/user/workers/w2", "/user/workers/w3")
    //#paths

    //#round-robin-pool-1
    val router1: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router1")
    //#round-robin-pool-1

    //#round-robin-pool-2
    val router2: ActorRef =
      context.actorOf(RoundRobinPool(5).props(Props[Worker]), "router2")
    //#round-robin-pool-2

    //#round-robin-group-1
    val router3: ActorRef =
      context.actorOf(FromConfig.props(), "router3")
    //#round-robin-group-1

    //#round-robin-group-2
    val router4: ActorRef =
      context.actorOf(RoundRobinGroup(paths).props(), "router4")
    //#round-robin-group-2  

    //#random-pool-1
    val router5: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router5")
    //#random-pool-1

    //#random-pool-2
    val router6: ActorRef =
      context.actorOf(RandomPool(5).props(Props[Worker]), "router6")
    //#random-pool-2

    //#random-group-1
    val router7: ActorRef =
      context.actorOf(FromConfig.props(), "router7")
    //#random-group-1

    //#random-group-2
    val router8: ActorRef =
      context.actorOf(RandomGroup(paths).props(), "router8")
    //#random-group-2

    //#smallest-mailbox-pool-1
    val router9: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router9")
    //#smallest-mailbox-pool-1

    //#smallest-mailbox-pool-2
    val router10: ActorRef =
      context.actorOf(SmallestMailboxPool(5).props(Props[Worker]), "router10")
    //#smallest-mailbox-pool-2

    //#broadcast-pool-1
    val router11: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router11")
    //#broadcast-pool-1

    //#broadcast-pool-2
    val router12: ActorRef =
      context.actorOf(BroadcastPool(5).props(Props[Worker]), "router12")
    //#broadcast-pool-2

    //#broadcast-group-1
    val router13: ActorRef =
      context.actorOf(FromConfig.props(), "router13")
    //#broadcast-group-1

    //#broadcast-group-2
    val router14: ActorRef =
      context.actorOf(BroadcastGroup(paths).props(), "router14")
    //#broadcast-group-2

    //#scatter-gather-pool-1
    val router15: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router15")
    //#scatter-gather-pool-1

    //#scatter-gather-pool-2
    val router16: ActorRef =
      context.actorOf(ScatterGatherFirstCompletedPool(5, within = 10.seconds).
        props(Props[Worker]), "router16")
    //#scatter-gather-pool-2

    //#scatter-gather-group-1
    val router17: ActorRef =
      context.actorOf(FromConfig.props(), "router17")
    //#scatter-gather-group-1

    //#scatter-gather-group-2
    val router18: ActorRef =
      context.actorOf(ScatterGatherFirstCompletedGroup(paths,
        within = 10.seconds).props(), "router18")
    //#scatter-gather-group-2  

    //#consistent-hashing-pool-1
    val router19: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router19")
    //#consistent-hashing-pool-1

    //#consistent-hashing-pool-2
    val router20: ActorRef =
      context.actorOf(ConsistentHashingPool(5).props(Props[Worker]),
        "router20")
    //#consistent-hashing-pool-2

    //#consistent-hashing-group-1
    val router21: ActorRef =
      context.actorOf(FromConfig.props(), "router21")
    //#consistent-hashing-group-1

    //#consistent-hashing-group-2
    val router22: ActorRef =
      context.actorOf(ConsistentHashingGroup(paths).props(), "router22")
    //#consistent-hashing-group-2  

    //#resize-pool-1
    val router23: ActorRef =
      context.actorOf(FromConfig.props(Props[Worker]), "router23")
    //#resize-pool-1

    //#resize-pool-2
    val resizer = DefaultResizer(lowerBound = 2, upperBound = 15)
    val router24: ActorRef =
      context.actorOf(RoundRobinPool(5, Some(resizer)).props(Props[Worker]),
        "router24")
    //#resize-pool-2  

    def receive = {
      case _ =>
    }

  }

  class Echo extends Actor {
    def receive = {
      case m => sender ! m
    }
  }
}

class RouterDocSpec extends AkkaSpec(RouterDocSpec.config) with ImplicitSender {

  import RouterDocSpec._

  //#create-workers
  system.actorOf(Props[Workers], "workers")
  //#create-workers

  //#create-parent
  system.actorOf(Props[Parent], "parent")
  //#create-parent

  "demonstrate dispatcher" in {
    //#dispatchers
    val router: ActorRef = system.actorOf(
      // “head” router actor will run on "router-dispatcher" dispatcher
      // Worker routees will run on "pool-dispatcher" dispatcher  
      RandomPool(5, routerDispatcher = "router-dispatcher").props(Props[Worker]),
      name = "poolWithDispatcher")
    //#dispatchers
  }

  "demonstrate broadcast" in {
    val router = system.actorOf(RoundRobinPool(nrOfInstances = 5).props(Props[Echo]))
    //#broadcastDavyJonesWarning
    import akka.routing.Broadcast
    router ! Broadcast("Watch out for Davy Jones' locker")
    //#broadcastDavyJonesWarning
    receiveN(5, 5.seconds.dilated) must have length (5)
  }

  "demonstrate PoisonPill" in {
    val router = watch(system.actorOf(RoundRobinPool(nrOfInstances = 5).props(Props[Echo])))
    //#poisonPill
    import akka.actor.PoisonPill
    router ! PoisonPill
    //#poisonPill
    expectTerminated(router)
  }

  "demonstrate broadcast of PoisonPill" in {
    val router = watch(system.actorOf(RoundRobinPool(nrOfInstances = 5).props(Props[Echo])))
    //#broadcastPoisonPill
    import akka.actor.PoisonPill
    import akka.routing.Broadcast
    router ! Broadcast(PoisonPill)
    //#broadcastPoisonPill
    expectTerminated(router)
  }

  "demonstrate Kill" in {
    val router = watch(system.actorOf(RoundRobinPool(nrOfInstances = 5).props(Props[Echo])))
    //#kill
    import akka.actor.Kill
    router ! Kill
    //#kill
    expectTerminated(router)
  }

  "demonstrate broadcast of Kill" in {
    val router = watch(system.actorOf(RoundRobinPool(nrOfInstances = 5).props(Props[Echo])))
    //#broadcastKill
    import akka.actor.Kill
    import akka.routing.Broadcast
    router ! Broadcast(Kill)
    //#broadcastKill
    expectTerminated(router)
  }

  "demonstrate remote deploy" in {
    //#remoteRoutees
    import akka.actor.{ Address, AddressFromURIString }
    import akka.remote.routing.RemoteRouterConfig
    val addresses = Seq(
      Address("akka.tcp", "remotesys", "otherhost", 1234),
      AddressFromURIString("akka.tcp://othersys@anotherhost:1234"))
    val routerRemote = system.actorOf(
      RemoteRouterConfig(RoundRobinPool(5), addresses).props(Props[Echo]))
    //#remoteRoutees
  }
}
