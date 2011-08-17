package akka.cluster.routing.scattergather.failover

import akka.config.Config
import akka.cluster._
import akka.actor.{ ActorRef, Actor }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import java.util.{ Collections, Set ⇒ JSet }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException
import java.lang.Thread
import akka.routing.Routing.Broadcast

object ScatterGatherFailoverMultiJvmSpec {

  val NrOfNodes = 2

  case class Shutdown(node: Option[String] = None)
  case class Sleep(node: String)

  class TestActor extends Actor with Serializable {

    def shutdownNode = new Thread() {
      override def run() {
        Thread.sleep(2000)
        Cluster.node.shutdown()
      }
    }.start()

    def receive = {
      case Shutdown(None) ⇒ shutdownNode
      case Sleep(node) if node.equals(Config.nodename) ⇒
        Thread sleep 100
        self.reply(Config.nodename)
      case Shutdown(Some(node)) if node.equals(Config.nodename) ⇒ shutdownNode
      case _ ⇒
        Thread sleep 100
        self.reply(Config.nodename)
    }
  }

}

class ScatterGatherFailoverMultiJvmNode1 extends MasterClusterTestNode {

  import ScatterGatherFailoverMultiJvmSpec._

  def testNodes = NrOfNodes

  "When the message is sent with ?, and all connections are up, router" must {
    "return the first came reponse" in {
      val ignoreExceptions = Seq(
        EventFilter[NotYetConnectedException],
        EventFilter[ConnectException],
        EventFilter[ClusterException])

      EventHandler.notify(TestEvent.Mute(ignoreExceptions))

      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      /*      
             FIXME: Uncomment, when custom routers will be fully supported (ticket #1109)  
             
             val actor = Actor.actorOf[TestActor]("service-hello").asInstanceOf[ClusterActorRef]

             identifyConnections(actor).size() must be(2)

             // since node1 is falling asleep, response from node2 is gathered
             (actor ? Broadcast(Sleep("node1"))).get.asInstanceOf[String] must be("node2")

             Thread sleep 100

             // since node2 shuts down during processing the message, response from node1 is gathered
             (actor ? Broadcast(Shutdown(Some("node2")))).get.asInstanceOf[String] must be("node1")

             */
      Cluster.barrier("waiting-for-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }

  def identifyConnections(actor: ActorRef): JSet[String] = {
    val set = new java.util.HashSet[String]
    for (i ← 0 until NrOfNodes * 2) {
      val value = (actor ? "foo").get.asInstanceOf[String]
      set.add(value)
    }
    set
  }
}

class ScatterGatherFailoverMultiJvmNode2 extends ClusterTestNode {

  import ScatterGatherFailoverMultiJvmSpec._

  "___" must {
    "___" in {

      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      /*      
             FIXME: Uncomment, when custom routers will be fully supported (ticket #1109)  
             Thread.sleep(30 *1000)
             */

      Cluster.barrier("waiting-for-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}
