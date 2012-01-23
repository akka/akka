/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.reflogic

import akka.cluster._
import akka.cluster.Cluster._
import akka.actor.Actor
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import akka.routing.RoutingException
import java.net.ConnectException
import java.nio.channels.{ ClosedChannelException, NotYetConnectedException }
import akka.cluster.LocalCluster._

object ClusterActorRefCleanupMultiJvmSpec {

  val NrOfNodes = 3

  class TestActor extends Actor with Serializable {
    def receive = {
      case _ ⇒ {}
    }
  }

}

class ClusterActorRefCleanupMultiJvmNode1 extends MasterClusterTestNode {

  import ClusterActorRefCleanupMultiJvmSpec._

  val testNodes = NrOfNodes

  "ClusterActorRef" must {
    "cleanup itself" ignore {
      Cluster.node.start()
      barrier("awaitStarted", NrOfNodes).await()

      val ref = Actor.actorOf(Props[ClusterActorRefCleanupMultiJvmSpec.TestActor]("service-test")

      ref.isInstanceOf[ClusterActorRef] must be(true)

      val clusteredRef = ref.asInstanceOf[ClusterActorRef]

      barrier("awaitActorCreated", NrOfNodes).await()

      //verify that all remote actors are there.
      clusteredRef.nrOfConnections must be(2)

      // ignore exceptions from killing nodes
      val ignoreExceptions = Seq(
        EventFilter[ClosedChannelException],
        EventFilter[NotYetConnectedException],
        EventFilter[RoutingException],
        EventFilter[ConnectException])

      EventHandler.notify(TestEvent.Mute(ignoreExceptions))

      //just some waiting to make sure that the node has died.
      Thread.sleep(5000)

      //send some request, this should trigger the cleanup
      try {
        clusteredRef ! "hello"
        clusteredRef ! "hello"
      } catch {
        case e: ClosedChannelException   ⇒
        case e: NotYetConnectedException ⇒
        case e: RoutingException         ⇒
      }

      barrier("node-3-dead", NrOfNodes - 1).await()

      //since the call to the node failed, the node must have been removed from the list.
      clusteredRef.nrOfConnections must be(1)

      //just some waiting to make sure that the node has died.
      Thread.sleep(5000)

      //trigger the cleanup.
      try {
        clusteredRef ! "hello"
        clusteredRef ! "hello"
      } catch {
        case e: ClosedChannelException   ⇒
        case e: NotYetConnectedException ⇒
        case e: RoutingException         ⇒
      }

      //now there must not be any remaining connections after the dead of the last actor.
      clusteredRef.nrOfConnections must be(0)

      //and lets make sure we now get the correct exception if we try to use the ref.
      intercept[RoutingException] {
        clusteredRef ! "Hello"
      }

      node.shutdown()
    }
  }
}

class ClusterActorRefCleanupMultiJvmNode2 extends ClusterTestNode {

  import ClusterActorRefCleanupMultiJvmSpec._

  val testNodes = NrOfNodes

  //we are only using the nodes for their capacity, not for testing on this node itself.
  "___" must {
    "___" ignore {
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          ClusterTestNode.exit(classOf[ClusterActorRefCleanupMultiJvmNode2].getName)
        }
      })

      Cluster.node.start()
      barrier("awaitStarted", NrOfNodes).await()

      barrier("awaitActorCreated", NrOfNodes).await()

      barrier("node-3-dead", NrOfNodes - 1).await()

      System.exit(0)
    }
  }
}

class ClusterActorRefCleanupMultiJvmNode3 extends ClusterTestNode {

  import ClusterActorRefCleanupMultiJvmSpec._

  val testNodes = NrOfNodes

  //we are only using the nodes for their capacity, not for testing on this node itself.
  "___" must {
    "___" ignore {
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          ClusterTestNode.exit(classOf[ClusterActorRefCleanupMultiJvmNode3].getName)
        }
      })

      Cluster.node.start()
      barrier("awaitStarted", NrOfNodes).await()

      barrier("awaitActorCreated", NrOfNodes).await()

      System.exit(0)
    }
  }
}
