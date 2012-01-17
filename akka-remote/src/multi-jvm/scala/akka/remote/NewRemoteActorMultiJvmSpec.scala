package akka.remote

import akka.actor.{ Actor, Props }
import akka.remote._
import akka.routing._
import akka.testkit._
import akka.util.duration._
import akka.dispatch.Await

object NewRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
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

class NewRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(NewRemoteActorMultiJvmSpec.nodeConfigs(0)) {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

class NewRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(NewRemoteActorMultiJvmSpec.nodeConfigs(1)) with DefaultTimeout {

  import NewRemoteActorMultiJvmSpec._

  val nodes = NrOfNodes

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      barrier("start")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      Await.result(actor ? "identify", timeout.duration) must equal("AkkaRemoteSpec@localhost:9991")

      barrier("done")
    }
  }
}

