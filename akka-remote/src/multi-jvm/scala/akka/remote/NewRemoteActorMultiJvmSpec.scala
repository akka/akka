package akka.remote

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.routing._
import akka.testkit._
import akka.util.duration._
import akka.dispatch.Await

object NewRemoteActorMultiJvmSpec {
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

class NewRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(NewRemoteActorMultiJvmSpec.node1Config) {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

class NewRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(NewRemoteActorMultiJvmSpec.node2Config) with DefaultTimeout {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("start")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      Await.result(actor ? "identify", timeout.duration) must equal("node1")

      barrier("done")
    }
  }
}

