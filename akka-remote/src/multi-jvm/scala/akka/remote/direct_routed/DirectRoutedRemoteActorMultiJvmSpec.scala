package akka.remote.direct_routed

import akka.remote._
import akka.routing._

import akka.actor.Actor
import akka.config.Config

object DirectRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ {
        reply(Config.nodename)
      }
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode1 extends MultiJvmSync {

  import DirectRoutedRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("setup")
      Remote.start()
      barrier("start")
      barrier("done")
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode2 extends MultiJvmSync {

  import DirectRoutedRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("setup")
      Remote.start()
      barrier("start")

      val actor = Actor.actorOf[SomeActor]("service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val result = (actor ? "identify").get
      result must equal("node1")

      barrier("done")
    }
  }
}

