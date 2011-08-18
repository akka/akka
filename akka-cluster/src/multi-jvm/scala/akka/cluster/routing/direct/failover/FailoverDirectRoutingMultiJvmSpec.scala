package akka.cluster.routing.direct.failover

import akka.config.Config
import scala.Predef._
import akka.cluster.{ ClusterActorRef, Cluster, MasterClusterTestNode, ClusterTestNode }
import akka.actor.{ ActorInitializationException, Actor }
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }
import java.net.ConnectException
import java.nio.channels.NotYetConnectedException

object FailoverDirectRoutingMultiJvmSpec {

  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    //println("---------------------------------------------------------------------------")
    //println("SomeActor has been created on node [" + Config.nodename + "]")
    //println("---------------------------------------------------------------------------")

    def receive = {
      case "identify" ⇒
        //println("The node received the 'identify' command: " + Config.nodename)
        self.reply(Config.nodename)
      case "die" ⇒
        //println("The node received the 'die' command: " + Config.nodename)
        Cluster.node.shutdown
    }
  }

}

class FailoverDirectRoutingMultiJvmNode1 extends MasterClusterTestNode {

  import FailoverDirectRoutingMultiJvmSpec._

  val testNodes = NrOfNodes

  "Direct Router" must {
    "not yet be able to failover to another node" in {

      //println("==================================================================================================")
      //println("                                 FAILOVER DIRECT ROUTING")
      //println("==================================================================================================")

      val ignoreExceptions = Seq(EventFilter[NotYetConnectedException], EventFilter[ConnectException])
      EventHandler.notify(TestEvent.Mute(ignoreExceptions))

      Cluster.node

      Cluster.barrier("waiting-for-begin", NrOfNodes).await()
      val actor = Actor.actorOf[SomeActor]("service-hello").start().asInstanceOf[ClusterActorRef]

      //println("retrieved identity was: " + (actor ? "identify").get)
      (actor ? "identify").get must equal("node2")

      actor ! "die"

      Thread.sleep(4000)

      intercept[ActorInitializationException] {
        actor ! "identify"
      }
    }
  }
}

class FailoverDirectRoutingMultiJvmNode2 extends ClusterTestNode {

  import FailoverDirectRoutingMultiJvmSpec._

  "___" must {
    "___" in {
      Cluster.node
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      Thread.sleep(30 * 1000)
    }
  }
}

