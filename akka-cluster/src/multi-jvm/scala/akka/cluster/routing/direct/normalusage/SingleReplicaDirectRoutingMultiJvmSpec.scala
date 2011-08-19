package akka.cluster.routing.direct.normalusage

import akka.actor.Actor
import akka.config.Config
import akka.cluster.{ ClusterActorRef, ClusterTestNode, MasterClusterTestNode, Cluster }
import akka.cluster.LocalCluster

object SingleReplicaDirectRoutingMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    //println("---------------------------------------------------------------------------")
    //println("SomeActor has been created on node [" + Config.nodename + "]")
    //println("---------------------------------------------------------------------------")

    def receive = {
      case "identify" ⇒ {
        //println("The node received the 'identify' command: " + Config.nodename)
        self.reply(Config.nodename)
      }
    }
  }
}

class SingleReplicaDirectRoutingMultiJvmNode1 extends MasterClusterTestNode {

  import SingleReplicaDirectRoutingMultiJvmSpec._

  val testNodes = NrOfNodes

  "___" must {
    "___" in {
      Cluster.node
      LocalCluster.barrier("waiting-for-begin", NrOfNodes).await()

      LocalCluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

class SingleReplicaDirectRoutingMultiJvmNode2 extends ClusterTestNode {

  import SingleReplicaDirectRoutingMultiJvmSpec._

  "Direct Router: when node send message to existing node it" must {
    "communicate with that node" in {
      Cluster.node
      LocalCluster.barrier("waiting-for-begin", NrOfNodes).await()

      val actor = Actor.actorOf[SomeActor]("service-hello").start().asInstanceOf[ClusterActorRef]
      actor.isRunning must be(true)

      val result = (actor ? "identify").get
      result must equal("node1")

      LocalCluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

