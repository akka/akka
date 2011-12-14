package akka.remote.new_remote_actor

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.testkit.DefaultTimeout
import akka.dispatch.Await

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

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      Await.result(actor ? "identify", timeout.duration) must equal("node1")

      barrier("done")
    }
  }
}

