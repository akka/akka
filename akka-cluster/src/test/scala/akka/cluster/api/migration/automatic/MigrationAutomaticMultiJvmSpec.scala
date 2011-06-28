/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.api.migration.automatic

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.actor._
import Actor._
import akka.cluster._
import ChangeListener._
import Cluster._
import DeploymentConfig._
import akka.config.Config
import akka.serialization.Serialization

import java.util.concurrent._

object MigrationAutomaticMultiJvmSpec {
  var NrOfNodes = 3

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }
}

class MigrationAutomaticMultiJvmNode1 extends WordSpec with MustMatchers {
  import MigrationAutomaticMultiJvmSpec._

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node3", NrOfNodes) {
      }

      barrier("start-node2", NrOfNodes) {
      }

      barrier("start-node1", NrOfNodes) {
        node.start()
      }

      barrier("store-actor-in-node1", NrOfNodes) {
        val serializer = Serialization.serializerFor(classOf[HelloWorld]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
        node.store(actorOf[HelloWorld]("hello-world"), 1, serializer)
      }

      node.shutdown()
    }
  }
}

class MigrationAutomaticMultiJvmNode2 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import MigrationAutomaticMultiJvmSpec._

  var isFirstReplicaNode = false

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node3", NrOfNodes) {
      }

      barrier("start-node2", NrOfNodes) {
        node.start()
      }

      barrier("start-node1", NrOfNodes) {
      }

      barrier("store-actor-in-node1", NrOfNodes) {
      }

      Thread.sleep(2000) // wait for fail-over

      barrier("check-fail-over", NrOfNodes - 1) {
        // both remaining nodes should now have the replica
        node.isInUseOnNode("hello-world") must be(true)
        val actorRef = Actor.registry.local.actorFor("hello-world").getOrElse(fail("Actor should have been in the local actor registry"))
        actorRef.address must be("hello-world")
        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      node.shutdown()
    }
  }
}

class MigrationAutomaticMultiJvmNode3 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import MigrationAutomaticMultiJvmSpec._

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node3", NrOfNodes) {
        node.start()
      }

      barrier("start-node2", NrOfNodes) {
      }

      barrier("start-node1", NrOfNodes) {
      }

      barrier("store-actor-in-node1", NrOfNodes) {
      }

      Thread.sleep(2000) // wait for fail-over

      barrier("check-fail-over", NrOfNodes - 1) {
        // both remaining nodes should now have the replica
        node.isInUseOnNode("hello-world") must be(true)
        val actorRef = Actor.registry.local.actorFor("hello-world").getOrElse(fail("Actor should have been in the local actor registry"))
        actorRef.address must be("hello-world")
        (actorRef ? "Hello").as[String].get must be("World from node [node3]")
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
