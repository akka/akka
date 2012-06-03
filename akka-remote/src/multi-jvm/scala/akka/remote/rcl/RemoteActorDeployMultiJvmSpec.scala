package akka.remote.rcl

import akka.actor.{Actor, Props}
import akka.dispatch.Await
import akka.pattern.ask
import akka.remote.{AbstractRemoteActorMultiJvmSpec, AkkaRemoteSpec}

object RemoteActorDeployMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  val NODE2_ACTOR_FQN = "akka.remote.rcl.RemoteActorDeployMultiJvmSpec$Node2EchoActor"

  class Node2EchoActor extends Actor {
    def receive = {
      case x => sender ! x
    }
  }

  import com.typesafe.config.ConfigFactory

  override def commonConfig = ConfigFactory.parseString( """
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-echo.remote = %s
        }
      }
      remote {
        transport = "akka.remote.netty.rcl.BlockingRemoteClassLoaderTransport"
        netty.message-frame-size = 10 MiB
      }
    }""" format akkaURIs(1))
}

import RemoteActorDeployMultiJvmSpec._

class RemoteActorDeployMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {

  import RemoteActorDeployMultiJvmSpec._

  System.setProperty("path_hole.filter", "*Node2*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RclBlockingClassLoader")

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_ACTOR_FQN)
      }
      barrier("start")
      barrier("done")
    }
  }
}

class RemoteActorDeployMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) {

  import RemoteActorDeployMultiJvmSpec._

  val nodes = NrOfNodes

  import akka.util.duration._

  implicit val timeout: akka.util.Timeout = 15 seconds

  "Sending a message from Node1 to Node2" must {

    "work even if the message class is not available on Node2" in {
      barrier("start")
      val verifyActor = system.actorOf(Props[Node2EchoActor], "service-echo")
      // we make sure stuff looks on the remote node as it looks on our node
      Await.result(verifyActor ? "foo", timeout.duration).asInstanceOf[String] must equal("foo")
      system.stop(verifyActor)
      barrier("done")
    }
  }

}