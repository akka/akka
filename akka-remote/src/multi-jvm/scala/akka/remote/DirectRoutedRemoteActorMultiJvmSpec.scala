package akka.remote

import akka.remote._
import akka.routing._
import akka.actor.{ Actor, Props }
import akka.testkit._
import akka.dispatch.Await

object DirectRoutedRemoteActorMultiJvmSpec {
  val NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! context.system.nodename
    }
  }

  import com.typesafe.config.ConfigFactory
  val commonConfig = ConfigFactory.parseString("""
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-hello.remote = "akka://AkkaRemoteSpec@localhost:9991"
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
      barrier("start")
      barrier("done")
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.node2Config) with DefaultTimeout {

  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("start")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      actor.isInstanceOf[RemoteActorRef] must be(true)

      Await.result(actor ? "identify", timeout.duration) must equal("node1")

      barrier("done")
    }
  }
}

