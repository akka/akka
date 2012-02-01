/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing.roundrobin.replicationfactor_2

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import Cluster._
import akka.cluster.LocalCluster._
import akka.actor._
import akka.actor.Actor._
import akka.config.Config
import scala.util.duration._
import scala.util.{ Duration, Timer }
import akka.cluster.LocalCluster._

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import akka.dispatch.Await

/**
 * When a MultiJvmNode is started, will it automatically be part of the cluster (so will it automatically be eligible
 * for running actors, or will it be just a 'client' talking to the cluster.
 */
object RoundRobin2ReplicasMultiJvmSpec {
  val NrOfNodes = 2

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        reply("World from node [" + Config.nodename + "]")
    }
  }
}

class RoundRobin2ReplicasMultiJvmNode1 extends MasterClusterTestNode {
  import RoundRobin2ReplicasMultiJvmSpec._

  val testNodes = NrOfNodes

  "Round Robin: A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      System.getProperty("akka.cluster.nodename", "") must be("node1")
      System.getProperty("akka.remote.port", "") must be("9991")

      //wait till node 1 has started.
      barrier("start-node1", NrOfNodes) {
        Cluster.node.start()
      }

      //wait till ndoe 2 has started.
      barrier("start-node2", NrOfNodes).await()

      //wait till an actor reference on node 2 has become available.
      barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        val timer = Timer(30.seconds, true)
        while (timer.isTicking && !node.isInUseOnNode("service-hello")) {}
      }

      //wait till the node 2 has send a message to the replica's.
      barrier("send-message-from-node2-to-replicas", NrOfNodes).await()

      node.shutdown()
    }
  }
}

class RoundRobin2ReplicasMultiJvmNode2 extends ClusterTestNode {
  import RoundRobin2ReplicasMultiJvmSpec._

  "Round Robin: A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      System.getProperty("akka.cluster.nodename", "") must be("node2")
      System.getProperty("akka.remote.port", "") must be("9992")

      //wait till node 1 has started.
      barrier("start-node1", NrOfNodes).await()

      //wait till node 2 has started.
      barrier("start-node2", NrOfNodes) {
        Cluster.node.start()
      }

      //check if the actorRef is the expected remoteActorRef.
      var hello: ActorRef = null
      barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf(Props[HelloWorld]("service-hello")
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

        for(i <- 1 to 8)
          count(Await.result((hello ? "Hello").mapTo[String], timeout.duration))

        replies.get("World from node [node1]").get must equal(4)
        replies.get("World from node [node2]").get must equal(4)
      }

      node.shutdown()
    }
  }
}
