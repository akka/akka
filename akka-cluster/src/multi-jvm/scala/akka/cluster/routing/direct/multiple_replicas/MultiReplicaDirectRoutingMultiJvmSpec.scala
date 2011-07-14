package akka.cluster.routing.direct.multiple_replicas

import akka.actor.Actor
import akka.cluster.{ MasterClusterTestNode, Cluster, ClusterTestNode }
import akka.config.Config

object MultiReplicaDirectRoutingMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    println("---------------------------------------------------------------------------")
    println("SomeActor has been created on node [" + Config.nodename + "]")
    println("---------------------------------------------------------------------------")

    def receive = {
      case "identify" ⇒ {
        println("The node received the 'identify' command: " + Config.nodename)
        self.reply(Config.nodename)
      }
    }
  }

}

class MultiReplicaDirectRoutingMultiJvmNode2 extends ClusterTestNode {

  import MultiReplicaDirectRoutingMultiJvmSpec._

  "when node send message to existing node using direct routing it" must {
    "communicate with that node" in {
      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      //Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes).await()

      val actor = Actor.actorOf[SomeActor]("service-hello")
      actor.start()

      //actor.start()
      val name: String = (actor ? "identify").get.asInstanceOf[String]

      println("The name of the actor was " + name)

      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

class MultiReplicaDirectRoutingMultiJvmNode1 extends MasterClusterTestNode {

  import MultiReplicaDirectRoutingMultiJvmSpec._

  val testNodes = NrOfNodes

  "node" must {
    "participate in cluster" in {
      Cluster.node.start()

      Cluster.barrier("waiting-for-begin", NrOfNodes).await()
      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

