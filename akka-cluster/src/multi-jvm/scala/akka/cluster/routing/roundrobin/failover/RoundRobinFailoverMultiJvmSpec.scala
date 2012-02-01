package akka.cluster.routing.roundrobin.failover

import akka.config.Config
import akka.cluster._
import akka.actor.{ ActorRef, Actor }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import scala.util.duration._
import scala.util.{ Duration, Timer }
import java.util.{ Collections, Set ⇒ JSet }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException
import java.lang.Thread
import akka.cluster.LocalCluster._
import akka.dispatch.Await

object RoundRobinFailoverMultiJvmSpec {

  val NrOfNodes = 3

  class SomeActor extends Actor with Serializable {

    def receive = {
      case "identify" ⇒
        reply(Config.nodename)
    }
  }

}

class RoundRobinFailoverMultiJvmNode1 extends MasterClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  def testNodes = NrOfNodes

  "Round Robin: when round robin router fails" must {
    "jump to another replica" ignore {
      val ignoreExceptions = Seq(
        EventFilter[NotYetConnectedException],
        EventFilter[ConnectException],
        EventFilter[ClusterException])

      var oldFoundConnections: JSet[String] = null
      var actor: ActorRef = null

      barrier("node-start", NrOfNodes) {
        EventHandler.notify(TestEvent.Mute(ignoreExceptions))
        Cluster.node.start()
      }

      barrier("actor-creation", NrOfNodes) {
        actor = Actor.actorOf(Props[SomeActor]("service-hello")
        actor.isInstanceOf[ClusterActorRef] must be(true)
      }

      val timer = Timer(30.seconds, true)
      while (timer.isTicking &&
        !Cluster.node.isInUseOnNode("service-hello", "node1") &&
        !Cluster.node.isInUseOnNode("service-hello", "node3")) {}
      //Thread.sleep(5000) // wait for all actors to start up on other nodes

      barrier("actor-usage", NrOfNodes) {
        Cluster.node.isInUseOnNode("service-hello") must be(true)
        oldFoundConnections = identifyConnections(actor)

        //since we have replication factor 2
        oldFoundConnections.size() must be(2)
      }

      Thread.sleep(5000) // wait for fail-over from node3

      barrier("verify-fail-over", NrOfNodes - 1) {
        val timer = Timer(30.seconds, true)
        while (timer.isTicking &&
          !Cluster.node.isInUseOnNode("service-hello", "node1") &&
          !Cluster.node.isInUseOnNode("service-hello", "node2")) {}

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
    for (i ← 0 until 100) {
      val value = Await.result(actor ? "identify", timeout.duration).asInstanceOf[String]
      set.add(value)
    }
    set
  }
}

class RoundRobinFailoverMultiJvmNode2 extends ClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  "___" must {
    "___" ignore {
      barrier("node-start", NrOfNodes) {
        Cluster.node.start()
      }

      barrier("actor-creation", NrOfNodes).await()
      barrier("actor-usage", NrOfNodes).await()

      Cluster.node.isInUseOnNode("service-hello") must be(false)

      Thread.sleep(5000) // wait for fail-over from node3

      barrier("verify-fail-over", NrOfNodes - 1).await()
    }
  }
}

class RoundRobinFailoverMultiJvmNode3 extends ClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  "___" must {
    "___" ignore {
      barrier("node-start", NrOfNodes) {
        Cluster.node.start()
      }

      barrier("actor-creation", NrOfNodes).await()
      barrier("actor-usage", NrOfNodes).await()

      Cluster.node.isInUseOnNode("service-hello") must be(true)

      Cluster.node.shutdown()
    }
  }
}

