package akka.remote

import akka.actor.{ Actor, ActorRef, Props }
import akka.testkit._
import akka.dispatch.Await
import akka.pattern.ask

object DirectRoutedRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! self
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
    }""" format akkaURIs(1))
}

import DirectRoutedRemoteActorMultiJvmSpec._

class DirectRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {
  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")
      barrier("done")
    }
  }
}

class DirectRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) with DefaultTimeout {

  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("start")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      actor.isInstanceOf[RemoteActorRef] must be(true)

      Await.result(actor ? "identify", timeout.duration).asInstanceOf[ActorRef].path.address.hostPort must equal(akkaSpec(0))

      // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(actor)
      barrier("done")
    }
  }
}
