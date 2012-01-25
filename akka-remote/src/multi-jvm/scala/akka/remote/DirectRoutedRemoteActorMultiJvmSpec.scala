package akka.remote

import akka.actor.{ Actor, Props }
import akka.testkit._
import akka.dispatch.Await

object DirectRoutedRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! self.path.address.hostPort
    }
  }

  import com.typesafe.config.ConfigFactory
  override def commonConfig = ConfigFactory.parseString("""
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-hello.remote = %s
        }
      }
    }""" format specString(1))
}

class DirectRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.nodeConfigs(0)) {
  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")
      barrier("done")
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.nodeConfigs(1)) with DefaultTimeout {

  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("start")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      actor.isInstanceOf[RemoteActorRef] must be(true)

      Await.result(actor ? "identify", timeout.duration) must equal("AkkaRemoteSpec@localhost:9991")

      barrier("done")
    }
  }
}

