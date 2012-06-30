package akka.remote.netty.rcl

import akka.remote.{AkkaRemoteSpec, AbstractRemoteActorMultiJvmSpec}
import akka.util.Timeout
import akka.actor.{ActorRef, Actor, Props}
import akka.dispatch.Await

// Node 1 forwards Node1Ping msg to Node 2 forwards to Node 3 and then Node 3 sends Node3 Pong back to Node 1
object ForwardMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 3

  class ForwardActor(forwardTo: ActorRef) extends Actor with Serializable {
    def receive = {
      case msg => forwardTo.tell(msg, sender)
    }
  }

  class PongActor extends Actor with Serializable {
    def receive = {
      case msg => sender ! Node3Pong
    }
  }

  val NODE1_PING_FQN = "akka.remote.netty.rcl.ForwardMultiJvmSpec$Node1Ping$"
  // case objects have $ at the end
  val NODE3_PONG_FQN = "akka.remote.netty.rcl.ForwardMultiJvmSpec$Node3Pong$"
  // case objects have $ at the end

  case object Node1Ping
  case object Node3Pong


  import com.typesafe.config.ConfigFactory

  override def commonConfig = ConfigFactory.parseString( """
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
      }
      remote.transport = akka.remote.netty.rcl.RemoteClassLoadingTransport
    }""")
}

import ForwardMultiJvmSpec._

class ForwardMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {

  import ForwardMultiJvmSpec._

  val nodes = NrOfNodes

  System.setProperty("path_hole.filter", "*Node2*,*Node3*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RemoteClassLoader")


  import akka.util.duration._
  import akka.pattern.ask

  implicit val timeout = Timeout(20 seconds)

  "___" must {
    "___" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE3_PONG_FQN)
      }
      barrier("start")
      val node2EchoActor = system.actorFor("akka://" + akkaSpec(1) + "/user/service-forward")
      Await.result(node2EchoActor ? Node1Ping, timeout.duration).asInstanceOf[AnyRef].getClass.getName must equal(NODE3_PONG_FQN)
      barrier("done")
    }
  }
}


class ForwardMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) {

  import ForwardMultiJvmSpec._

  val nodes = NrOfNodes

  import akka.util.duration._

  System.setProperty("path_hole.filter", "*Node1*,*Node3*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RemoteClassLoader")

  implicit val timeout = Timeout(20 seconds)

  "Remote class loading" must {
    "properly work with messages that contain methods" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE1_PING_FQN)
        Class.forName(NODE3_PONG_FQN)
      }
      barrier("start")
      val node3PongActor = system.actorFor("akka://" + akkaSpec(2) + "/user/service-echo")
      system.actorOf(Props {
        new ForwardActor(node3PongActor)
      }, "service-forward")
      barrier("done")
    }
  }
}

class ForwardMultiJvmNode3 extends AkkaRemoteSpec(nodeConfigs(2)) {

  import ForwardMultiJvmSpec._

  val nodes = NrOfNodes

  import akka.util.duration._

  implicit val timeout = Timeout(20 seconds)

  System.setProperty("path_hole.filter", "*Node1*,*Node2*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RemoteClassLoader")

  "Remote class loading" must {
    "properly work with messages that contain methods" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE1_PING_FQN)
      }
      barrier("start")
      system.actorOf(Props[PongActor], "service-echo")
      barrier("done")
    }
  }
}