/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing.roundrobin.replicationfactor_1

import akka.cluster._
import Cluster._
import akka.actor._
import akka.config.Config
import akka.cluster.LocalCluster._

/**
 * Test that if a single node is used with a round robin router with replication factor then the actor is instantiated on the single node.
 */
object RoundRobin1ReplicaMultiJvmSpec {

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒ reply("World from node [" + Config.nodename + "]")
    }
  }

}

class RoundRobin1ReplicaMultiJvmNode1 extends MasterClusterTestNode {

  import RoundRobin1ReplicaMultiJvmSpec._

  val testNodes = 1

  "Round Robin: A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      Cluster.node.start()

      var hello = Actor.actorOf(Props[HelloWorld]("service-hello")
      hello must not equal (null)
      hello.address must equal("service-hello")
      hello.isInstanceOf[ClusterActorRef] must be(true)

      hello must not equal (null)
      val reply = (hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1"))
      reply must equal("World from node [node1]")

      node.shutdown()
    }
  }
}
