package akka.cluster.routing.direct.bad_address

import akka.cluster.{ Cluster, MasterClusterTestNode }
import akka.actor.Actor
import akka.config.Config

object BadAddressDirectRoutingMultiJvmSpec {

  val NrOfNodes = 1

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

class BadAddressDirectRoutingMultiJvmNode1 extends MasterClusterTestNode {

  import BadAddressDirectRoutingMultiJvmSpec._

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

