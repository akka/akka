package akka.cluster.routing.homenode

import akka.config.Config
import akka.actor.{ ActorRef, Actor }
import akka.cluster.{ ClusterTestNode, MasterClusterTestNode, Cluster }
import Cluster._

object HomeNodeMultiJvmSpec {

  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ {
        self.reply(Config.nodename)
      }
    }
  }
}

class HomeNodeMultiJvmNode1 extends MasterClusterTestNode {

  import HomeNodeMultiJvmSpec._

  val testNodes = NrOfNodes

  "A Router" must {
    "obey 'home-node' config option when instantiated actor in cluster" in {

      node.start()
      barrier("waiting-for-begin", NrOfNodes).await()

      barrier("get-ref-to-actor-on-node2", NrOfNodes).await()

      node.shutdown()
    }
  }
}

class HomeNodeMultiJvmNode2 extends ClusterTestNode {

  import HomeNodeMultiJvmSpec._

  "A Router" must {
    "obey 'home-node' config option when instantiated actor in cluster" in {

      node.start()
      barrier("waiting-for-begin", NrOfNodes).await()

      barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        val actor = Actor.actorOf[SomeActor]("service-hello")
        val name = (actor ? "identify").get.asInstanceOf[String]
        name must equal("node1")
      }

      node.shutdown()
    }
  }
}
