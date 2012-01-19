/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.api.registry

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
import akka.cluster.LocalCluster._

import java.util.concurrent._

object RegistryStoreMultiJvmSpec {
  var NrOfNodes = 2

  class HelloWorld1 extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        reply("World from node [" + Config.nodename + "]")
    }
  }

  class HelloWorld2 extends Actor with Serializable {
    var counter = 0
    def receive = {
      case "Hello" ⇒
        Thread.sleep(1000)
        counter += 1
      case "Count" ⇒
        reply(counter)
    }
  }
}

class RegistryStoreMultiJvmNode1 extends MasterClusterTestNode {
  import RegistryStoreMultiJvmSpec._

  val testNodes = NrOfNodes

  "A cluster" must {

    "be able to store an ActorRef in the cluster without a replication strategy and retrieve it with 'use'" in {

      barrier("start-node-1", NrOfNodes) {
        Cluster.node.start()
      }

      barrier("start-node-2", NrOfNodes).await()

      barrier("store-1-in-node-1", NrOfNodes) {
        node.store("hello-world-1", classOf[HelloWorld1], Serialization.serializerFor(classOf[HelloWorld1]))
      }

      barrier("use-1-in-node-2", NrOfNodes).await()

      barrier("store-2-in-node-1", NrOfNodes) {
        node.store("hello-world-2", classOf[HelloWorld1], false, Serialization.serializerFor(classOf[HelloWorld1]))
      }

      barrier("use-2-in-node-2", NrOfNodes).await()

      node.shutdown()
    }
  }
}

class RegistryStoreMultiJvmNode2 extends ClusterTestNode {
  import RegistryStoreMultiJvmSpec._

  "A cluster" must {

    "be able to store an actor in the cluster with 'store' and retrieve it with 'use'" in {

      barrier("start-node-1", NrOfNodes).await()

      barrier("start-node-2", NrOfNodes) {
        Cluster.node.start()
      }

      barrier("store-1-in-node-1", NrOfNodes).await()

      barrier("use-1-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world-1")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")

        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world-1")

        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      barrier("store-2-in-node-1", NrOfNodes).await()

      barrier("use-2-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world-2")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")

        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world-2")

        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      node.shutdown()
    }
  }
}
