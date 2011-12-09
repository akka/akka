package akka.remote.scatter_gather_routed

import akka.actor.Actor
import akka.remote._
import akka.routing._
import akka.routing.Routing.Broadcast
import akka.testkit.DefaultTimeout

object ScatterGatherRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 4
  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ sender ! context.system.nodename
      case "end" ⇒ self.stop()
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode3 extends AkkaRemoteSpec {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec with DefaultTimeout {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a ScatterGather router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("start")
      val actor = system.actorOf[SomeActor]("service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)
      //actor.asInstanceOf[RoutedActorRef].router.isInstanceOf[ScatterGatherFirstCompletedRouter] must be(true)

      val connectionCount = NrOfNodes - 1
      val iterationCount = 10

      var replies = Map(
        "node1" -> 0,
        "node2" -> 0,
        "node3" -> 0)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val nodeName = (actor ? "hit").as[String].getOrElse(fail("No id returned by actor"))
          replies = replies + (nodeName -> (replies(nodeName) + 1))
        }
      }

      barrier("broadcast-end")
      actor ! Broadcast("end")

      barrier("end")
      replies.values foreach { _ must be(10) }

      barrier("done")
    }
  }
}

