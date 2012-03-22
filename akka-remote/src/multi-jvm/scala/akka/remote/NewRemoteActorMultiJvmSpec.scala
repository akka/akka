package akka.remote

import akka.actor.{ Actor, ActorRef, Props }
import akka.testkit._
import akka.dispatch.Await
import akka.pattern.ask

object NewRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
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
      Await.result(actor ? "identify", timeout.duration).asInstanceOf[ActorRef].path.address.hostPort must equal(akkaSpec(0))

      // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(actor)
      barrier("done")
    }
  }
}

