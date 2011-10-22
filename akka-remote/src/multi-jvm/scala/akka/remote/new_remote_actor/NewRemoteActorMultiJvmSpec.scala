package akka.remote.new_remote_actor

import akka.actor.Actor
import akka.remote._

object NewRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! app.nodename
    }
  }
}

class NewRemoteActorMultiJvmNode1 extends AkkaRemoteSpec {

  import NewRemoteActorMultiJvmSpec._

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

class NewRemoteActorMultiJvmNode2 extends AkkaRemoteSpec {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("setup")

      remote.start()

      barrier("start")

      val actor = app.actorOf[SomeActor]("service-hello")
      val result = (actor ? "identify").get
      result must equal("node1")

      barrier("done")
    }
  }
}

