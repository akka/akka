package akka.cluster.routing.routing_identity_problem

import akka.config.Config
import akka.cluster.Cluster
import akka.actor.{ ActorRef, Actor }
import akka.cluster.routing.{ SlaveNode, MasterNode }

object RoutingIdentityProblemMultiJvmSpec {

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

class RoutingIdentityProblemMultiJvmNode1 extends MasterNode {

  import RoutingIdentityProblemMultiJvmSpec._

  "foo" must {
    "bla" in {
      Cluster.node.start()

      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      var hello: ActorRef = null
      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf[SomeActor]("service-hello")
      }

      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

class RoutingIdentityProblemMultiJvmNode2 extends SlaveNode {

  import RoutingIdentityProblemMultiJvmSpec._

  "foo" must {
    "bla" in {
      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {}

      val actor = Actor.actorOf[SomeActor]("service-hello")
      val name: String = (actor ? "identify").get.asInstanceOf[String]
      //todo: Jonas: this is the line that needs to be uncommented to get the test to fail.
      //name must equal("node1")

      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}
