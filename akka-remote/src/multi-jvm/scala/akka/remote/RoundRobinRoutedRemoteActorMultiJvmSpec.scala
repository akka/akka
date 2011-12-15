package akka.remote

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.routing._
import akka.testkit.DefaultTimeout
import akka.dispatch.Await

object RoundRobinRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 4
  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ sender ! context.system.nodename
      case "end" ⇒ context.stop(self)
    }
  }

  import com.typesafe.config.ConfigFactory
  val commonConfig = ConfigFactory.parseString("""
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-hello.router = "round-robin"
          /service-hello.nr-of-instances = 3
          /service-hello.target.nodes = ["akka://AkkaRemoteSpec@localhost:9991","akka://AkkaRemoteSpec@localhost:9992","akka://AkkaRemoteSpec@localhost:9993"]
        }
      }
      remote.server.hostname = "localhost"
    }""")

  val node1Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9991"
      cluster.nodename = "node1"
    }""") withFallback commonConfig

  val node2Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9992"
      cluster.nodename = "node2"
    }""") withFallback commonConfig

  val node3Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9993"
      cluster.nodename = "node3"
    }""") withFallback commonConfig

  val node4Config = ConfigFactory.parseString("""
    akka {
      remote.server.port = "9994"
      cluster.nodename = "node4"
    }""") withFallback commonConfig
}

class RoundRobinRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(RoundRobinRoutedRemoteActorMultiJvmSpec.node1Config) {
  import RoundRobinRoutedRemoteActorMultiJvmSpec._
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

class RoundRobinRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(RoundRobinRoutedRemoteActorMultiJvmSpec.node2Config) {
  import RoundRobinRoutedRemoteActorMultiJvmSpec._
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

class RoundRobinRoutedRemoteActorMultiJvmNode3 extends AkkaRemoteSpec(RoundRobinRoutedRemoteActorMultiJvmSpec.node3Config) {
  import RoundRobinRoutedRemoteActorMultiJvmSpec._
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

class RoundRobinRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec(RoundRobinRoutedRemoteActorMultiJvmSpec.node4Config) with DefaultTimeout {
  import RoundRobinRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a RoundRobin router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("start")
      val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val connectionCount = NrOfNodes - 1
      val iterationCount = 10

      var replies = Map(
        "node1" -> 0,
        "node2" -> 0,
        "node3" -> 0)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val nodeName = Await.result(actor ? "hit", timeout.duration).toString
          replies = replies + (nodeName -> (replies(nodeName) + 1))
        }
      }

      barrier("broadcast-end")
      actor ! Broadcast("end")

      barrier("end")
      replies.values foreach { _ must be(10) }

      barrier("done")
    }
  }
}

