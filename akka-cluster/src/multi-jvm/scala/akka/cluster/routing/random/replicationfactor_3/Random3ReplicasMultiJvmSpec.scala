/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing.random.replicationfactor_3

import akka.cluster._
import akka.actor._
import akka.config.Config
import Cluster._
import akka.cluster.LocalCluster._
import akka.dispatch.Await

/**
 * When a MultiJvmNode is started, will it automatically be part of the cluster (so will it automatically be eligible
 * for running actors, or will it be just a 'client' talking to the cluster.
 */
object Random3ReplicasMultiJvmSpec {
  val NrOfNodes = 3

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        reply("World from node [" + Config.nodename + "]")
    }
  }
}

/**
 * What is the purpose of this node? Is this just a node for the cluster to make use of?
 */
class Random3ReplicasMultiJvmNode1 extends MasterClusterTestNode {

  import Random3ReplicasMultiJvmSpec._

  def testNodes: Int = NrOfNodes

  "___" must {
    "___" in {
      Cluster.node.start()

      barrier("start-nodes", NrOfNodes).await()

      barrier("create-actor", NrOfNodes).await()

      barrier("end-test", NrOfNodes).await()

      node.shutdown()
    }
  }
}

class Random3ReplicasMultiJvmNode2 extends ClusterTestNode {

  import Random3ReplicasMultiJvmSpec._
  import Cluster._

  "Random: A cluster" must {

    "distribute requests randomly" in {
      Cluster.node.start()

      //wait till node 1 has started.
      barrier("start-nodes", NrOfNodes).await()

      //check if the actorRef is the expected remoteActorRef.
      var hello: ActorRef = null
      hello = Actor.actorOf(Props[HelloWorld]("service-hello")
      hello must not equal (null)
      hello.address must equal("service-hello")
      hello.isInstanceOf[ClusterActorRef] must be(true)

      barrier("create-actor", NrOfNodes).await()

      val replies = collection.mutable.Map.empty[String, Int]
      def count(reply: String) = {
        if (replies.get(reply).isEmpty) replies.put(reply, 1)
        else replies.put(reply, replies(reply) + 1)
      }

      for (i ← 0 until 1000) {
        count(Await.result((hello ? "Hello").mapTo[String], 10 seconds))
      }

      val repliesNode1 = replies("World from node [node1]")
      val repliesNode2 = replies("World from node [node2]")
      val repliesNode3 = replies("World from node [node3]")

      assert(repliesNode1 > 100)
      assert(repliesNode2 > 100)
      assert(repliesNode3 > 100)
      assert(repliesNode1 + repliesNode2 + repliesNode3 === 1000)

      barrier("end-test", NrOfNodes).await()

      node.shutdown()
    }
  }
}

class Random3ReplicasMultiJvmNode3 extends ClusterTestNode {

  import Random3ReplicasMultiJvmSpec._
  import Cluster._

  "___" must {
    "___" in {
      Cluster.node.start()

      barrier("start-nodes", NrOfNodes).await()

      barrier("create-actor", NrOfNodes).await()

      barrier("end-test", NrOfNodes).await()

      node.shutdown()
    }
  }
}
