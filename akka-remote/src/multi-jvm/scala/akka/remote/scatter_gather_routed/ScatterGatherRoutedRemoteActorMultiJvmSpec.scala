package akka.remote.scatter_gather_routed

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.routing._
import akka.testkit._
import akka.util.duration._

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

class ScatterGatherRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec with DefaultTimeout with ImplicitSender {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a ScatterGather router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("start")
      val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)
      //actor.asInstanceOf[RoutedActorRef].router.isInstanceOf[ScatterGatherFirstCompletedRouter] must be(true)

      val connectionCount = NrOfNodes - 1
      val iterationCount = 10

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          actor ! "hit"
        }
      }

      val replies = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
        case name: String ⇒ (name, 1)
      }).foldLeft(Map("node1" -> 0, "node2" -> 0, "node3" -> 0)) {
        case (m, (n, c)) ⇒ m + (n -> (m(n) + c))
      }

      barrier("broadcast-end")
      actor ! Broadcast("end")

      barrier("end")
      replies.values.sum must be === connectionCount * iterationCount

      barrier("done")
    }
  }
}

