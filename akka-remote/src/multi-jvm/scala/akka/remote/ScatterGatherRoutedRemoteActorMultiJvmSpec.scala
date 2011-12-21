package akka.remote

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.routing._
import akka.testkit._
import akka.util.duration._

object ScatterGatherRoutedRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 4
  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ sender ! context.system.nodename
      case "end" ⇒ context.stop(self)
    }
  }

  import com.typesafe.config.ConfigFactory
  override def commonConfig = ConfigFactory.parseString("""
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-hello.router = "scatter-gather"
          /service-hello.nr-of-instances = 3
          /service-hello.target.nodes = ["akka://AkkaRemoteSpec@localhost:9991","akka://AkkaRemoteSpec@localhost:9992","akka://AkkaRemoteSpec@localhost:9993"]
        }
      }
    }""")
}

class ScatterGatherRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(ScatterGatherRoutedRemoteActorMultiJvmSpec.nodeConfigs(0)) {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(ScatterGatherRoutedRemoteActorMultiJvmSpec.nodeConfigs(1)) {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode3 extends AkkaRemoteSpec(ScatterGatherRoutedRemoteActorMultiJvmSpec.nodeConfigs(2)) {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "___" must {
    "___" in {
      barrier("start")
      barrier("broadcast-end")
      barrier("end")
      barrier("done")
    }
  }
}

class ScatterGatherRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec(ScatterGatherRoutedRemoteActorMultiJvmSpec.nodeConfigs(3))
  with DefaultTimeout with ImplicitSender {
  import ScatterGatherRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a ScatterGather router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("start")
      val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val connectionCount = NrOfNodes - 1
      val iterationCount = 10

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          actor ! "hit"
        }
      }

      val replies = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
        case name: String ⇒ (name, 1)
      }).foldLeft(Map("node1" -> 0, "node2" -> 0, "node3" -> 0)) {
        case (m, (n, c)) ⇒ m + (n -> (m(n) + c))
      }

      barrier("broadcast-end")
      actor ! Broadcast("end")

      barrier("end")
      replies.values.sum must be === connectionCount * iterationCount

      barrier("done")
    }
  }
}

