package akka.remote

import akka.remote._
import akka.routing._
import akka.actor.Actor
import akka.testkit._

object DirectRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! system.nodename
    }
  }

  import com.typesafe.config.ConfigFactory
  val commonConfig = ConfigFactory.parseString("""
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /app/service-hello.router = "direct"
          /app/service-hello.nr-of-instances = 1
          /app/service-hello.remote.nodes = ["localhost:9991"]
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
}

class DirectRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.node1Config) {
  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("setup")
      remote.start()
      barrier("start")
      barrier("done")
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.node2Config) {
  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("setup")
      remote.start()
      barrier("start")

      val actor = system.actorOf[SomeActor]("service-hello")
      actor.isInstanceOf[RoutedActorRef] must be(true)

      val result = (actor ? "identify").get
      result must equal("node1")

      barrier("done")
    }
  }
}

