/*
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.migration.automatic

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.actor._
import akka.cluster._
import Cluster._
import akka.config.Config
import akka.serialization.Serialization

object MigrationAutomaticMultiJvmSpec {
  var NrOfNodes = 3

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }
}

class MigrationAutomaticMultiJvmNode1 extends ClusterTestNode {
  import MigrationAutomaticMultiJvmSpec._

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node1", NrOfNodes) {
        Cluster.node
      }

      barrier("create-actor-on-node1", NrOfNodes) {
        val actorRef = Actor.actorOf[HelloWorld]("hello-world").start()
        node.isInUseOnNode("hello-world") must be(true)
        actorRef.address must be("hello-world")
        (actorRef ? "Hello").as[String].get must be("World from node [node1]")
      }

      barrier("start-node2", NrOfNodes) {
      }

      node.shutdown()
    }
  }
}

class MigrationAutomaticMultiJvmNode2 extends ClusterTestNode {
  import MigrationAutomaticMultiJvmSpec._

  var isFirstReplicaNode = false

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node1", NrOfNodes) {
      }

      barrier("create-actor-on-node1", NrOfNodes) {
      }

      barrier("start-node2", NrOfNodes) {
        Cluster.node
      }

      Thread.sleep(2000) // wait for fail-over from node1 to node2

      barrier("check-fail-over-to-node2", NrOfNodes - 1) {
        // both remaining nodes should now have the replica
        node.isInUseOnNode("hello-world") must be(true)
        val actorRef = Actor.registry.local.actorFor("hello-world")
          .getOrElse(fail("Actor should have been in the local actor registry"))
        actorRef.address must be("hello-world")
        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      barrier("start-node3", NrOfNodes - 1) {
      }

      node.shutdown()
    }
  }
}

class MigrationAutomaticMultiJvmNode3 extends MasterClusterTestNode {
  import MigrationAutomaticMultiJvmSpec._

  val testNodes = NrOfNodes

  "A cluster" must {

    "be able to migrate an actor from one node to another" in {

      barrier("start-node1", NrOfNodes) {
      }

      barrier("create-actor-on-node1", NrOfNodes) {
      }

      barrier("start-node2", NrOfNodes) {
      }

      barrier("check-fail-over-to-node2", NrOfNodes - 1) {
      }

      barrier("start-node3", NrOfNodes - 1) {
        Cluster.node
      }

      Thread.sleep(2000) // wait for fail-over from node2 to node3

      barrier("check-fail-over-to-node3", NrOfNodes - 2) {
        // both remaining nodes should now have the replica
        node.isInUseOnNode("hello-world") must be(true)
        val actorRef = Actor.registry.local.actorFor("hello-world")
          .getOrElse(fail("Actor should have been in the local actor registry"))
        actorRef.address must be("hello-world")
        (actorRef ? "Hello").as[String].get must be("World from node [node3]")
      }

      node.shutdown()
    }
  }
}
