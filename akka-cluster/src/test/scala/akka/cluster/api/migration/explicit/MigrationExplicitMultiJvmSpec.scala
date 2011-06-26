/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.api.migration.explicit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.actor._
import Actor._
import akka.cluster._
import ChangeListener._
import Cluster._
import akka.config.Config
import akka.serialization.Serialization

import java.util.concurrent._

object MigrationExplicitMultiJvmSpec {
  var NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }
}

class MigrationExplicitMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import MigrationExplicitMultiJvmSpec._

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node-1", NrOfNodes) {
        node.start()
      }

      barrier("start-node-2", NrOfNodes) {
      }

      barrier("store-1-in-node-1", NrOfNodes) {
        val serializer = Serialization.serializerFor(classOf[HelloWorld]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
        node.store(actorOf[HelloWorld]("hello-world"), serializer)
      }

      barrier("use-1-in-node-2", NrOfNodes) {
      }

      barrier("migrate-from-node2-to-node1", NrOfNodes) {
      }

      barrier("check-actor-is-moved-to-node1", NrOfNodes) {
        node.isInUseOnNode("hello-world") must be(true)

        val actorRef = Actor.registry.local.actorFor("hello-world").getOrElse(fail("Actor should have been in the local actor registry"))
        actorRef.address must be("hello-world")
        (actorRef ? "Hello").as[String].get must be("World from node [node1]")
      }

      node.shutdown()
    }
  }

  override def beforeAll() = {
    startLocalCluster()
  }

  override def afterAll() = {
    shutdownLocalCluster()
  }
}

class MigrationExplicitMultiJvmNode2 extends WordSpec with MustMatchers {
  import MigrationExplicitMultiJvmSpec._

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node-1", NrOfNodes) {
      }

      barrier("start-node-2", NrOfNodes) {
        node.start()
      }

      barrier("store-1-in-node-1", NrOfNodes) {
      }

      barrier("use-1-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")

        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world")

        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      barrier("migrate-from-node2-to-node1", NrOfNodes) {
        node.migrate(NodeAddress(node.nodeAddress.clusterName, "node1"), "hello-world")
        Thread.sleep(2000)
      }

      barrier("check-actor-is-moved-to-node1", NrOfNodes) {
      }

      node.shutdown()
    }
  }
}
