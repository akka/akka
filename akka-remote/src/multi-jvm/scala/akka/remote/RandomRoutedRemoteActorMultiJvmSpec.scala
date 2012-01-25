package akka.remote

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.routing._
import akka.testkit.DefaultTimeout
import akka.dispatch.Await
import akka.pattern.ask

object RandomRoutedRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 4
  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hit" ⇒ sender ! self.path.address.hostPort
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
          /service-hello.router = "random"
          /service-hello.nr-of-instances = %d
          /service-hello.target.nodes = [%s]
        }
      }
    }""" format (3, akkaURIs(3)))
}

class RandomRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.nodeConfigs(0)) {
  import RandomRoutedRemoteActorMultiJvmSpec._
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

class RandomRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.nodeConfigs(1)) {
  import RandomRoutedRemoteActorMultiJvmSpec._
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

class RandomRoutedRemoteActorMultiJvmNode3 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.nodeConfigs(2)) {
  import RandomRoutedRemoteActorMultiJvmSpec._
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

class RandomRoutedRemoteActorMultiJvmNode4 extends AkkaRemoteSpec(RandomRoutedRemoteActorMultiJvmSpec.nodeConfigs(3)) with DefaultTimeout {
  import RandomRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  "A new remote actor configured with a Random router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      barrier("start")
      val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val connectionCount = NrOfNodes - 1
      val iterationCount = 10

      var replies = Map(
        akkaSpec(0) -> 0,
        akkaSpec(1) -> 0,
        akkaSpec(2) -> 0)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val nodeName = Await.result(actor ? "hit", timeout.duration).toString
          replies = replies + (nodeName -> (replies(nodeName) + 1))
        }
      }

      barrier("broadcast-end")
      actor ! Broadcast("end")

      barrier("end")
      replies.values foreach { _ must be > (0) }

      barrier("done")
    }
  }
}

