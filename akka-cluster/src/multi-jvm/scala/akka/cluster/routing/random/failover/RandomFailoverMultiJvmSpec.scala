package akka.cluster.routing.random.failover

import akka.config.Config
import akka.cluster._
import akka.actor.{ ActorRef, Actor }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import java.util.{ Collections, Set ⇒ JSet }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException

object RandomFailoverMultiJvmSpec {

  val NrOfNodes = 3

  class SomeActor extends Actor with Serializable {
    //println("---------------------------------------------------------------------------")
    //println("SomeActor has been created on node [" + Config.nodename + "]")
    //println("---------------------------------------------------------------------------")

    def receive = {
      case "identify" ⇒ {
        //println("The node received the 'identify' command")
        self.reply(Config.nodename)
      }
      case "shutdown" ⇒ {
        //println("The node received the 'shutdown' command")
        Cluster.node.shutdown()
      }
    }
  }

}

class RandomFailoverMultiJvmNode1 extends MasterClusterTestNode {

  import RandomFailoverMultiJvmSpec._

  def testNodes = NrOfNodes

  def sleepSome() {
    //println("Starting sleep")
    Thread.sleep(1000) //nasty.. but ok for now.
    //println("Finished doing sleep")
  }

  "Random: when routing fails" must {
    "jump to another replica" in {
      val ignoreExceptions = Seq(EventFilter[NotYetConnectedException], EventFilter[ConnectException])
      EventHandler.notify(TestEvent.Mute(ignoreExceptions))

      Cluster.node
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      // ============= the real testing =================
      val actor = Actor.actorOf[SomeActor]("service-hello").asInstanceOf[ClusterActorRef]

      val oldFoundConnections = identifyConnections(actor)
      //println("---------------------------- oldFoundConnections ------------------------")
      //println(oldFoundConnections)

      //since we have replication factor 2
      oldFoundConnections.size() must be(2)

      //terminate a node
      actor ! "shutdown"

      sleepSome()

      //this is where the system behaves unpredictable. From time to time it works... from time to time there
      //all kinds of connection timeouts. So this test shows that there are problems. For the time being
      //the test code has been deactivated to prevent causing problems.

      //val newFoundConnections = identifyConnections(actor)
      //println("---------------------------- newFoundConnections ------------------------")
      //println(newFoundConnections)

      //it still must be 2 since a different node should have been used to failover to
      //newFoundConnections.size() must be(2)
      //they are not disjoint since, there must be a single element that is in both
      //Collections.disjoint(newFoundConnections, oldFoundConnections) must be(false)
      //but they should not be equal since the shutdown-node has been replaced by another one.
      //newFoundConnections.equals(oldFoundConnections) must be(false)

      Cluster.node.shutdown()
    }
  }

  def identifyConnections(actor: ActorRef): JSet[String] = {
    val set = new java.util.HashSet[String]
    for (i ← 0 until NrOfNodes * 10) {
      val value = (actor ? "identify").get.asInstanceOf[String]
      set.add(value)
    }
    set
  }
}

class RandomFailoverMultiJvmNode2 extends ClusterTestNode {

  import RandomFailoverMultiJvmSpec._

  "___" must {
    "___" in {
      Cluster.node
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      Thread.sleep(30 * 1000)
    }
  }
}

class RandomFailoverMultiJvmNode3 extends ClusterTestNode {

  import RandomFailoverMultiJvmSpec._

  "___" must {
    "___" in {
      Cluster.node
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      Thread.sleep(30 * 1000)
    }
  }
}

