package akka.cluster.routing.roundrobin.failover

import akka.config.Config
import akka.cluster._
import akka.actor.{ ActorRef, Actor }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import java.util.{ Collections, Set ⇒ JSet }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException
import java.lang.Thread
import akka.cluster.LocalCluster._

object RoundRobinFailoverMultiJvmSpec {

  val NrOfNodes = 3

  class SomeActor extends Actor with Serializable {

    def receive = {
      case "identify" ⇒ {
        self.reply(Config.nodename)
      }
      case "shutdown" ⇒ {
        new Thread() {
          override def run() {
            Thread.sleep(2000)
            Cluster.node.shutdown()
          }
        }.start()
      }
    }
  }

}

class RoundRobinFailoverMultiJvmNode1 extends MasterClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  def testNodes = NrOfNodes

  "Round Robin: when round robin router fails" must {
    "jump to another replica" in {
      val ignoreExceptions = Seq(
        EventFilter[NotYetConnectedException],
        EventFilter[ConnectException],
        EventFilter[ClusterException])

      var oldFoundConnections: JSet[String] = null
      var actor: ActorRef = null

      barrier("node-start", NrOfNodes) {
        EventHandler.notify(TestEvent.Mute(ignoreExceptions))
        Cluster.node
      }

      barrier("actor-creation", NrOfNodes) {
        actor = Actor.actorOf[SomeActor]("service-hello")
        actor.isInstanceOf[ClusterActorRef] must be(true)

        // val actor2 = Actor.registry.local.actorFor("service-hello")
        //   .getOrElse(fail("Actor should have been in the local actor registry"))

        Cluster.node.isInUseOnNode("service-hello") must be(true)
        oldFoundConnections = identifyConnections(actor)

        //since we have replication factor 2
        oldFoundConnections.size() must be(2)
      }

      Thread.sleep(5000) // wait for fail-over from node3

      barrier("verify-fail-over", NrOfNodes - 1) {
        val newFoundConnections = identifyConnections(actor)

        //it still must be 2 since a different node should have been used to failover to
        newFoundConnections.size() must be(2)

        //they are not disjoint since, there must be a single element that is in both
        Collections.disjoint(newFoundConnections, oldFoundConnections) must be(false)

        //but they should not be equal since the shutdown-node has been replaced by another one.
        newFoundConnections.equals(oldFoundConnections) must be(false)
      }

      Cluster.node.shutdown()
    }
  }

  def identifyConnections(actor: ActorRef): JSet[String] = {
    val set = new java.util.HashSet[String]
    for (i ← 0 until NrOfNodes * 2) {
      val value = (actor ? "identify").get.asInstanceOf[String]
      set.add(value)
    }
    set
  }
}

class RoundRobinFailoverMultiJvmNode2 extends ClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  "___" must {
    "___" in {
      barrier("node-start", NrOfNodes) {
        Cluster.node
      }

      barrier("actor-creation", NrOfNodes).await()

      Cluster.node.isInUseOnNode("service-hello") must be(false)

      Thread.sleep(5000) // wait for fail-over from node3

      barrier("verify-fail-over", NrOfNodes - 1).await()
    }
  }
}

class RoundRobinFailoverMultiJvmNode3 extends ClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  "___" must {
    "___" in {
      barrier("node-start", NrOfNodes) {
        Cluster.node
      }

      barrier("actor-creation", NrOfNodes).await()

      Cluster.node.isInUseOnNode("service-hello") must be(true)

      Cluster.node.shutdown()
    }
  }
}

