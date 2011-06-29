package akka.cluster.routing.peterexample

import org.scalatest.matchers.MustMatchers
import akka.config.Config
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import akka.cluster.Cluster
import akka.actor.{ ActorRef, Actor }

object PeterExampleMultiJvmSpec {

  val NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    println("---------------------------------------------------------------------------")
    println("HelloWorldActor has been created on node [" + Config.nodename + "]")
    println("---------------------------------------------------------------------------")

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

class PeterExampleMultiJvmNode1 extends TestNode {

  import PeterExampleMultiJvmSpec._

  "foo" must {
    "bla" in {
      /*
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

      println("Successfully acquired reference")

      println("Saying hello to actor")
      hello ! "say hello"
      Cluster.node.shutdown()  */
    }
  }
}

class PeterExampleMultiJvmNode2 extends WordSpec with MustMatchers with BeforeAndAfterAll {

  import PeterExampleMultiJvmSpec._
  /*

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
  }                           */
}
