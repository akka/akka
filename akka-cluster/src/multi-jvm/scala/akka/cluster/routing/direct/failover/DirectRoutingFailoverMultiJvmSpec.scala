package akka.cluster.routing.direct.failover

import akka.config.Config
import scala.Predef._
import akka.cluster.{ ClusterActorRef, Cluster, MasterClusterTestNode, ClusterTestNode }
import akka.actor.{ ActorInitializationException, Actor, ActorRef }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException
import akka.cluster.LocalCluster

object DirectRoutingFailoverMultiJvmSpec {

  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {

    def receive = {
      case "identify" ⇒
        self.reply(Config.nodename)
    }
  }
}

class DirectRoutingFailoverMultiJvmNode1 extends MasterClusterTestNode {

  import DirectRoutingFailoverMultiJvmSpec._

  val testNodes = NrOfNodes

  "Direct Router" must {
    "throw exception [ActorInitializationException] upon fail-over" ignore {

      val ignoreExceptions = Seq(EventFilter[NotYetConnectedException], EventFilter[ConnectException])
      EventHandler.notify(TestEvent.Mute(ignoreExceptions))

      var actor: ActorRef = null

      LocalCluster.barrier("node-start", NrOfNodes) {
        Cluster.node
      }

      LocalCluster.barrier("actor-creation", NrOfNodes) {
        actor = Actor.actorOf[SomeActor]("service-hello")
      }

      LocalCluster.barrier("verify-actor", NrOfNodes) {
        (actor ? "identify").get must equal("node2")
      }

      Thread.sleep(5000) // wait for fail-over from node2

      LocalCluster.barrier("verify-fail-over", NrOfNodes - 1) {
        actor ! "identify" // trigger failure and removal of connection to node2
        intercept[Exception] {
          actor ! "identify" // trigger exception since no more connections
        }
      }

      Cluster.node.shutdown()
    }
  }
}

class DirectRoutingFailoverMultiJvmNode2 extends ClusterTestNode {

  import DirectRoutingFailoverMultiJvmSpec._

  "___" must {
    "___" ignore {
      LocalCluster.barrier("node-start", NrOfNodes) {
        Cluster.node
      }

      LocalCluster.barrier("actor-creation", NrOfNodes) {
      }

      LocalCluster.barrier("verify-actor", NrOfNodes) {
        Cluster.node.isInUseOnNode("service-hello") must be(true)
      }

      Cluster.node.shutdown()
    }
  }
}

