package akka.remote.random_routed

import akka.actor.Actor
import akka.remote._
import akka.routing._
import akka.routing.Routing.Broadcast

object RandomRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 4
  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ channel ! app.nodename
      case "end" ⇒ self.stop()
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode3 extends AkkaRemoteSpec {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class RandomRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a Random router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("setup")
      remote.start()

      barrier("start")
      val actor = app.actorOf[SomeActor]("service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

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
      replies.values foreach { _ must be > (0) }

      barrier("done")
    }
  }
}

