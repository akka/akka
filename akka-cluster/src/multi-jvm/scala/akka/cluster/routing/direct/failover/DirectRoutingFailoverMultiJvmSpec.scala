package akka.cluster.routing.direct.failover

import akka.config.Config
import scala.Predef._
import akka.cluster.{ ClusterActorRef, Cluster, MasterClusterTestNode, ClusterTestNode }
import akka.actor.{ ActorInitializationException, Actor, ActorRef }
import scala.util.duration._
import scala.util.{ Duration, Timer }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException
import akka.cluster.LocalCluster
import akka.dispatch.Await

object DirectRoutingFailoverMultiJvmSpec {

  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {

    def receive = {
      case "identify" ⇒
        reply(Config.nodename)
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
        Cluster.node.start()
      }

      LocalCluster.barrier("actor-creation", NrOfNodes) {
        actor = Actor.actorOf(Props[SomeActor]("service-hello")
      }

      LocalCluster.barrier("verify-actor", NrOfNodes) {
        Await.result(actor ? "identify", timeout.duration) must equal("node2")
      }

      val timer = Timer(30.seconds, true)
      while (timer.isTicking && !Cluster.node.isInUseOnNode("service-hello")) {}

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
        Cluster.node.start()
      }

      LocalCluster.barrier("actor-creation", NrOfNodes).await()

      LocalCluster.barrier("verify-actor", NrOfNodes) {
        Cluster.node.isInUseOnNode("service-hello") must be(true)
      }

      Cluster.node.shutdown()
    }
  }
}

