package akka.remote.new_remote_actor

import akka.actor.Actor
import akka.remote._
import akka.testkit.DefaultTimeout

object NewRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! context.system.nodename
    }
  }
}

class NewRemoteActorMultiJvmNode1 extends AkkaRemoteSpec {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

class NewRemoteActorMultiJvmNode2 extends AkkaRemoteSpec with DefaultTimeout {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("start")

      val actor = system.actorOf[SomeActor]("service-hello")
      val result = (actor ? "identify").get
      result must equal("node1")

      barrier("done")
    }
  }
}

