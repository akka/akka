package akka.cluster.routing.direct.single_replica

import akka.actor.Actor
import akka.config.Config
import akka.cluster.{ ClusterTestNode, MasterClusterTestNode, Cluster }

object SingleReplicaDirectRoutingMultiJvmSpec {
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

class SingleReplicaDirectRoutingMultiJvmNode1 extends MasterClusterTestNode {

  import SingleReplicaDirectRoutingMultiJvmSpec._

  val testNodes = NrOfNodes

  "when node send message to existing node using direct routing it" must {
    "communicate with that node" in {
      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      val actor = Actor.actorOf[SomeActor]("service-hello").start()
      actor.isRunning must be(true)

      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

class SingleReplicaDirectRoutingMultiJvmNode2 extends ClusterTestNode {

  import SingleReplicaDirectRoutingMultiJvmSpec._

  "___" must {
    "___" in {
      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

