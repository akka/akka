package akka.cluster.routing.use_homenode_as_replica

import org.scalatest.matchers.MustMatchers
import akka.config.Config
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import akka.cluster._
import Cluster._
import akka.actor.{ ActorRef, Actor }

object UseHomeNodeAsReplicaMultiJvmSpec {
  val NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case x: String ⇒ {
        println("Hello message was received")
      }
    }
  }
}

class UseHomeNodeAsReplicaMultiJvmNode1 extends MasterClusterTestNode {

  import UseHomeNodeAsReplicaMultiJvmSpec._

  val testNodes = NrOfNodes

  "foo" must {
    "bla" in {
      println("Node 1 has started")

      barrier("start-node1", NrOfNodes) {
        node.start()
      }

      barrier("start-node2", NrOfNodes) {}

      println("Getting reference to service-hello actor")
      var hello: ActorRef = null
      barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf[HelloWorld]("service-hello")
      }

      println("Saying hello to actor")
      hello ! "say hello"
      node.shutdown()
    }
  }
}

class UseHomeNodeAsReplicaMultiJvmNode2 extends ClusterTestNode {

  import UseHomeNodeAsReplicaMultiJvmSpec._
  "foo" must {
    "bla" in {
      println("Waiting for Node 1 to start")
      barrier("start-node1", NrOfNodes) {}

      println("Waiting for himself to start???")
      barrier("start-node2", NrOfNodes) {
        node.start()
      }

      barrier("get-ref-to-actor-on-node2", NrOfNodes) {}

      println("Shutting down JVM Node 2")
      node.shutdown()
    }
  }
}
