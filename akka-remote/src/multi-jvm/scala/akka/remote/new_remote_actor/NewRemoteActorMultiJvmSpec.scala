package akka.remote.new_remote_actor

import akka.remote._

import akka.actor.Actor
import akka.config.Config

object NewRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ {
        self.reply(Config.nodename)
      }
    }
  }
}

class NewRemoteActorMultiJvmNode1 extends MasterClusterTestNode {

  import NewRemoteActorMultiJvmSpec._

  val testNodes = NrOfNodes

  "___" must {
    "___" in {
      Remote.start()
      Thread.sleep(5000)
    }
  }
}

class NewRemoteActorMultiJvmNode2 extends ClusterTestNode {

  import NewRemoteActorMultiJvmSpec._

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      Remote.start()

      val actor = Actor.actorOf[SomeActor]("service-hello")

      val result = (actor ? "identify").get
      result must equal("node1")
    }
  }
}

