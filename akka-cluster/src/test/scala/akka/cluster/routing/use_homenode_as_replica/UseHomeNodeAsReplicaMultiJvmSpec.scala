package akka.cluster.routing.use_homenode_as_replica

import org.scalatest.matchers.MustMatchers
import akka.config.Config
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import akka.cluster.Cluster
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

class TestNode extends WordSpec with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() {
    Cluster.startLocalCluster()
  }

  override def afterAll() {
    Cluster.shutdownLocalCluster()
  }
}

class UseHomeNodeAsReplicaMultiJvmNode1 extends TestNode {

  import UseHomeNodeAsReplicaMultiJvmSpec._

  "foo" must {
    "bla" in {
      println("Node 1 has started")

      Cluster.barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }

      Cluster.barrier("start-node2", NrOfNodes) {}

      println("Getting reference to service-hello actor")
      var hello: ActorRef = null
      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf[HelloWorld]("service-hello")
      }

      println("Saying hello to actor")
      hello ! "say hello"
      Cluster.node.shutdown()
    }
  }
}

class UseHomeNodeAsReplicaMultiJvmNode2 extends WordSpec with MustMatchers with BeforeAndAfterAll {

  import UseHomeNodeAsReplicaMultiJvmSpec._
  "foo" must {
    "bla" in {
      println("Waiting for Node 1 to start")
      Cluster.barrier("start-node1", NrOfNodes) {}

      println("Waiting for himself to start???")
      Cluster.barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {}

      println("Shutting down JVM Node 2")
      Cluster.node.shutdown()
    }
  }
}
