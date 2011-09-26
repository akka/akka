package akka.remote.new_remote_actor

import akka.remote._

import akka.actor.Actor
import akka.config.Config

object NewRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ {
        reply(Config.nodename)
      }
    }
  }
}

class NewRemoteActorMultiJvmNode1 extends MultiJvmSync {

  import NewRemoteActorMultiJvmSpec._

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

class NewRemoteActorMultiJvmNode2 extends MultiJvmSync {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("setup")

      Remote.start()

      barrier("start")

      val actor = Actor.actorOf[SomeActor]("service-hello")
      val result = (actor ? "identify").get
      result must equal("node1")

      barrier("done")
    }
  }
}

