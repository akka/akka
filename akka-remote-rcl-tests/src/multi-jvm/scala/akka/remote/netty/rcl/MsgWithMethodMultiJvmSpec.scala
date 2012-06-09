package akka.remote.netty.rcl

import akka.remote.{RemoteActorRef, AkkaRemoteSpec, AbstractRemoteActorMultiJvmSpec}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.{Actor, Props}

object MsgWithMethodMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  class SomeActor extends Actor with Serializable {

    def receive = {
      case bo: BinaryOperation => {
        sender ! bo.doIt(7, 9)
      }
      case _ => // drop it
    }
  }

  trait BinaryOperation extends Serializable {
    def doIt(a: Int, b: Int): Int
  }

  class Node2AddOperation extends BinaryOperation {
    def doIt(a: Int, b: Int) = a + b
  }

  class Node2MultiplyOperation extends BinaryOperation {
    def doIt(a: Int, b: Int) = a * b
  }

  val NODE2_ADD_OP_FQN = "akka.remote.netty.rcl.MsgWithMethodMultiJvmSpec$Node2AddOperation"
  val NODE2_MULTIPLY_OP_FQN = "akka.remote.netty.rcl.MsgWithMethodMultiJvmSpec$Node2MultiplyOperation"

  import com.typesafe.config.ConfigFactory

  override def commonConfig = ConfigFactory.parseString( """
    akka {
      loglevel = "DEBUG"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-hello.remote = %s
        }
      }
      remote.transport = akka.remote.netty.rcl.RemoteClassLoadingTransport
      remote.netty.message-frame-size = 10 MiB
    }""" format akkaURIs(1))
}

import MsgWithMethodMultiJvmSpec._

class MsgWithMethodMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {

  import MsgWithMethodMultiJvmSpec._

  val nodes = NrOfNodes

  // need to make sure Node1 does NOT have Node2* classes on the classpath
  // this requires a bit of heavy lifting since Multi-JVM plugin starts 2 JVMs but both point to the same classpath on disk
  // lets use the path-hole agent to hide the classes from the classpath
  // this will make any ClassLoaders exept for the unfiltered ones to throw CNFE even if the class is on the classpath
  System.setProperty("path_hole.filter", "*Node2*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RemoteClassLoader")

  "___" must {
    "___" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_ADD_OP_FQN)
      }
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_MULTIPLY_OP_FQN)
      }
      barrier("start")
      barrier("done")
    }
  }
}


class MsgWithMethodMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) {

  import MsgWithMethodMultiJvmSpec._

  val nodes = NrOfNodes

  import akka.util.duration._

  implicit val timeout = Timeout(20 seconds)


  "Remote class loading" must {
    "properly work with messages that contain methods" in {
      barrier("start")
      val actor = system.actorOf(Props[SomeActor], "service-hello")
      actor.isInstanceOf[RemoteActorRef] must be(true)

      Await.result(actor ? new Node2AddOperation, timeout.duration).asInstanceOf[Int] must equal(16)
      Await.result(actor ? new Node2MultiplyOperation, timeout.duration).asInstanceOf[Int] must equal(63)

      // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(actor)
      barrier("done")
    }
  }
}