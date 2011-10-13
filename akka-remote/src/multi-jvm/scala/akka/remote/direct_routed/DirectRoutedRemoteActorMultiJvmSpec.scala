package akka.remote.direct_routed

import akka.remote._
import akka.routing._
import akka.actor.Actor
import akka.testkit._

object DirectRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ {
        reply(app.nodename)
      }
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec {

  import DirectRoutedRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("setup")

      remote.start()

      barrier("start")
      barrier("done")
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec {

  import DirectRoutedRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("setup")

      remote.start()

      barrier("start")

      val actor = app.createActor[SomeActor]("service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val result = (actor ? "identify").get
      result must equal("node1")

      barrier("done")
    }
  }
}

