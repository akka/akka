package akka.cluster.routing.roundrobin_failover

import akka.config.Config
import akka.cluster._
import akka.actor.{ ActorRef, Actor }

object RoundRobinFailoverMultiJvmSpec {

  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    println("---------------------------------------------------------------------------")
    println("SomeActor has been created on node [" + Config.nodename + "]")
    println("---------------------------------------------------------------------------")

    def receive = {
      case "identify" ⇒ {
        println("The node received the 'identify' command")
        self.reply(Config.nodename)
      }
      case "shutdown" ⇒ {
        println("The node received the 'shutdown' command")
        Cluster.node.shutdown()
      }
    }
  }
}

class RoundRobinFailoverMultiJvmNode1 extends MasterClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  val testNodes = NrOfNodes

  "foo" must {
    "bla" in {
      Cluster.node.start()
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()

      println("Getting reference to service-hello actor")
      var hello: ActorRef = null
      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes) {
        hello = Actor.actorOf[SomeActor]("service-hello")
      }

      println("Successfully acquired reference")

      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      Cluster.node.shutdown()
    }
  }
}

class RoundRobinFailoverMultiJvmNode2 extends ClusterTestNode {

  import RoundRobinFailoverMultiJvmSpec._

  "foo" must {
    "bla" in {
      println("Started Zookeeper Node")
      Cluster.node.start()
      println("Waiting to begin")
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()
      println("Begin!")

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes).await()

      // ============= the real testing =================
      /*
      val actor = Actor.actorOf[SomeActor]("service-hello")
      val firstTimeResult = (actor ? "identify").get
      val secondTimeResult = (actor ? "identify").get
      //since there are only 2 nodes, the identity should not have changed.
      assert(firstTimeResult == secondTimeResult)

      //if we now terminate the node that
      actor ! "shutdown"

      //todo: do some waiting
      println("Doing some sleep")
      try {
        Thread.sleep(4000) //nasty.. but ok for now.
        println("Finished doing sleep")
      } finally {
        println("Ended the Thread.sleep method somehow..")
      }

      //now we should get a different node that responds to us since there was a failover.
      val thirdTimeResult = (actor ? "identify").get
      assert(!(firstTimeResult == thirdTimeResult))  */
      // ==================================================

      println("Waiting to end")
      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      println("Shutting down ClusterNode")
      Cluster.node.shutdown()
    }
  }
}

/*
class RoundRobinFailoverMultiJvmNode3 extends SlaveNode {

  import RoundRobinFailoverMultiJvmSpec._

  "foo" must {
    "bla" in {
      println("Started Zookeeper Node")
      Cluster.node.start()
      println("Waiting to begin")
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()
      println("Begin!")

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes).await()

      println("Waiting to end")
      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      println("Shutting down ClusterNode")
      Cluster.node.shutdown()
    }
  }
}

class RoundRobinFailoverMultiJvmNode4 extends SlaveNode {

  import RoundRobinFailoverMultiJvmSpec._

  "foo" must {
    "bla" in {
      println("Started Zookeeper Node")
      Cluster.node.start()
      println("Waiting to begin")
      Cluster.barrier("waiting-for-begin", NrOfNodes).await()
      println("Begin!")

      Cluster.barrier("get-ref-to-actor-on-node2", NrOfNodes).await()

      println("Waiting to end")
      Cluster.barrier("waiting-to-end", NrOfNodes).await()
      println("Shutting down ClusterNode")
      Cluster.node.shutdown()
    }
  }
} */
