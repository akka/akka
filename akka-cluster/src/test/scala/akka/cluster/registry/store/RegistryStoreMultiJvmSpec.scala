/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.registry.store

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

object RegistryStoreMultiJvmSpec {
  var NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }
}

class RegistryStoreMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import RegistryStoreMultiJvmSpec._

  "A cluster" must {

    "be able to store an ActorRef in the cluster without a replication strategy and retrieve it with 'use'" in {

      barrier("start-node-1", NrOfNodes) {
        node.start()
      }

      barrier("start-node-2", NrOfNodes) {
      }

      barrier("store-in-node-1", NrOfNodes) {
        val serializer = Serialization.serializerFor(classOf[HelloWorld]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
        node.store(actorOf[HelloWorld]("hello-world-1"), serializer)
      }

      barrier("use-in-node-2", NrOfNodes) {
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

class RegistryStoreMultiJvmNode2 extends WordSpec with MustMatchers {
  import RegistryStoreMultiJvmSpec._

  "A cluster" must {

    "be able to store an actor in the cluster with 'store' and retrieve it with 'use'" in {

      barrier("start-node-1", NrOfNodes) {
      }

      barrier("start-node-2", NrOfNodes) {
        node.start()
      }

      barrier("store-in-node-1", NrOfNodes) {
      }

      barrier("use-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world-1")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")
        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world-1")
      }

      node.shutdown()
    }
  }
}
