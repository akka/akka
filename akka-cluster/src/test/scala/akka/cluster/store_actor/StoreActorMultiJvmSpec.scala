/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.store_actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import akka.actor._
import Actor._

object StoreActorMultiJvmSpec {
  val NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒ self.reply("World")
    }
  }
}

class StoreActorMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import StoreActorMultiJvmSpec._

  "A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      System.getProperty("akka.cluster.nodename", "") must be("node1")
      System.getProperty("akka.cluster.port", "") must be("9991")

      Cluster.barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }

      Cluster.barrier("start-node2", NrOfNodes) {}

      Cluster.barrier("create-clustered-actor-node1", NrOfNodes) {
        val pi = Actor.actorOf[HelloWorld]("service-hello")
        pi must not equal (null)
        pi.address must equal("service-hello")
        pi.isInstanceOf[LocalActorRef] must be(true)
      }

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {}

      Cluster.node.shutdown()
    }
  }

  override def beforeAll() = {
    Cluster.startLocalCluster()
  }

  override def afterAll() = {
    Cluster.shutdownLocalCluster()
  }
}

class StoreActorMultiJvmNode2 extends WordSpec with MustMatchers {
  import StoreActorMultiJvmSpec._

  "A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      System.getProperty("akka.cluster.nodename", "") must be("node2")
      System.getProperty("akka.cluster.port", "") must be("9992")

      Cluster.barrier("start-node1", NrOfNodes) {}

      Cluster.barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }

      Cluster.barrier("create-clustered-actor-node1", NrOfNodes) {}

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        val pi = Actor.actorOf[HelloWorld]("service-hello")
        pi must not equal (null)
        pi.address must equal("service-hello")
        pi.isInstanceOf[ClusterActorRef] must be(true)
      }

      Cluster.node.shutdown()
    }
  }
}
