/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing.roundrobin.replicationfactor_2

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import Cluster._
import LocalCluster._
import akka.actor._
import akka.actor.Actor._
import akka.config.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import akka.util.Duration

/**
 * When a MultiJvmNode is started, will it automatically be part of the cluster (so will it automatically be eligible
 * for running actors, or will it be just a 'client' talking to the cluster.
 */
object RoundRobin2ReplicasMultiJvmSpec {
  val NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }
}

/**
 * What is the purpose of this node? Is this just a node for the cluster to make use of?
 */

class RoundRobin2ReplicasMultiJvmNode1 extends MasterClusterTestNode {
  import RoundRobin2ReplicasMultiJvmSpec._

  val testNodes = NrOfNodes

  "Round Robin: A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      System.getProperty("akka.cluster.nodename", "") must be("node1")
      System.getProperty("akka.cluster.port", "") must be("9991")

      //wait till node 1 has started.
      barrier("start-node1", NrOfNodes) {
        Cluster.node
      }

      //wait till ndoe 2 has started.
      barrier("start-node2", NrOfNodes) {
      }

      //wait till node 3 has started.
      barrier("start-node3", NrOfNodes) {
      }

      //wait till an actor reference on node 2 has become available.
      barrier("get-ref-to-actor-on-node2", NrOfNodes) {
      }

      //wait till the node 2 has send a message to the replica's.
      barrier("send-message-from-node2-to-replicas", NrOfNodes) {
      }

      node.shutdown()
    }
  }
}

class RoundRobin2ReplicasMultiJvmNode2 extends ClusterTestNode {
  import RoundRobin2ReplicasMultiJvmSpec._

  "Round Robin: A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      System.getProperty("akka.cluster.nodename", "") must be("node2")
      System.getProperty("akka.cluster.port", "") must be("9992")

      //wait till node 1 has started.
      barrier("start-node1", NrOfNodes) {
      }

      //wait till node 2 has started.
      barrier("start-node2", NrOfNodes) {
        Cluster.node
      }

      //wait till node 3 has started.
      barrier("start-node3", NrOfNodes) {
      }

      //check if the actorRef is the expected remoteActorRef.
      var hello: ActorRef = null
      barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf[HelloWorld]("service-hello")
        hello must not equal (null)
        hello.address must equal("service-hello")
        hello.isInstanceOf[ClusterActorRef] must be(true)
      }

      barrier("send-message-from-node2-to-replicas", NrOfNodes) {
        //todo: is there a reason to check for null again since it already has been done in the previous block.
        hello must not equal (null)

        val replies = new ConcurrentHashMap[String, AtomicInteger]()
        def count(reply: String) = {
          val counter = new AtomicInteger(0)
          Option(replies.putIfAbsent(reply, counter)).getOrElse(counter).incrementAndGet()
        }

        implicit val timeout = Timeout(Duration(20, "seconds"))

        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1")))
        count((hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node2")))

        replies.get("World from node [node1]").get must equal(4)
        replies.get("World from node [node2]").get must equal(4)
      }

      node.shutdown()
    }
  }
}