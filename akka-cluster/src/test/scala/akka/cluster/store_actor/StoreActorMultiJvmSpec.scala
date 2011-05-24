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
import akka.config.Config

object StoreActorMultiJvmSpec {
  val NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        println("GOT HELLO on NODE: " + Config.nodename)
        self.reply("World from node [" + Config.nodename + "]")
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
        val hello = Actor.actorOf[HelloWorld]("service-hello")
        hello must not equal (null)
        hello.address must equal("service-hello")
        hello.isInstanceOf[LocalActorRef] must be(true)
      }

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {}

      Cluster.barrier("send-message-from-node2-to-node1", NrOfNodes) {}

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

      var hello: ActorRef = null
      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf[HelloWorld]("service-hello")
        hello must not equal (null)
        hello.address must equal("service-hello")
        hello.isInstanceOf[ClusterActorRef] must be(true)
      }

      Cluster.barrier("send-message-from-node2-to-node1", NrOfNodes) {
        hello must not equal (null)
        val reply = (hello !! "Hello").as[String].getOrElse(fail("Should have recieved reply from node1"))
        reply must equal("World from node [node1]")
      }

      Cluster.node.shutdown()
    }
  }
}
