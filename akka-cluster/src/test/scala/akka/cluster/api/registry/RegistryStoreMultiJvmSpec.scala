/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
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

import java.util.concurrent._

object RegistryStoreMultiJvmSpec {
  var NrOfNodes = 2

  class HelloWorld1 extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }

  class HelloWorld2 extends Actor with Serializable {
    var counter = 0
    def receive = {
      case "Hello" ⇒
        Thread.sleep(1000)
        counter += 1
      case "Count" ⇒
        self.reply(counter)
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

      barrier("store-1-in-node-1", NrOfNodes) {
        val serializer = Serialization.serializerFor(classOf[HelloWorld1]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
        node.store(actorOf[HelloWorld1]("hello-world-1"), serializer)
      }

      barrier("use-1-in-node-2", NrOfNodes) {
      }

      barrier("store-2-in-node-1", NrOfNodes) {
        val serializer = Serialization.serializerFor(classOf[HelloWorld1]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
        node.store("hello-world-2", classOf[HelloWorld1], false, serializer)
      }

      barrier("use-2-in-node-2", NrOfNodes) {
      }

      barrier("store-3-in-node-1", NrOfNodes) {
        val serializer = Serialization.serializerFor(classOf[HelloWorld2]).fold(x ⇒ fail("No serializer found"), s ⇒ s)
        val actor = actorOf[HelloWorld2]("hello-world-3").start
        actor ! "Hello"
        actor ! "Hello"
        actor ! "Hello"
        actor ! "Hello"
        actor ! "Hello"
        node.store(actor, true, serializer)
      }

      barrier("use-3-in-node-2", NrOfNodes) {
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

      barrier("store-1-in-node-1", NrOfNodes) {
      }

      barrier("use-1-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world-1")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")

        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world-1")

        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      barrier("store-2-in-node-1", NrOfNodes) {
      }

      barrier("use-2-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world-2")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")

        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world-2")

        (actorRef ? "Hello").as[String].get must be("World from node [node2]")
      }

      barrier("store-3-in-node-1", NrOfNodes) {
      }

      barrier("use-3-in-node-2", NrOfNodes) {
        val actorOrOption = node.use("hello-world-3")
        if (actorOrOption.isEmpty) fail("Actor could not be retrieved")

        val actorRef = actorOrOption.get
        actorRef.address must be("hello-world-3")

        (actorRef ? ("Count", 30000)).as[Int].get must be >= (2) // be conservative - can by 5 but also 2 if slow system
      }

      node.shutdown()
    }
  }
}
