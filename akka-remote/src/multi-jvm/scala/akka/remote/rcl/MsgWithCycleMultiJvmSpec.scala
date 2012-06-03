package akka.remote.rcl

import akka.actor.{Actor, Props}
import akka.dispatch.Await
import akka.pattern.ask
import akka.remote.{AbstractRemoteActorMultiJvmSpec, AkkaRemoteSpec}
import org.scalatest.matchers.MustMatchers
import org.fest.reflect.core.Reflection._
import reflect.BeanProperty

object MsgWithCycleMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2


  val NODE2_BAZ_FQN = "akka.remote.rcl.MsgWithCycleMultiJvmSpec$Node2Baz"
  val NODE2_BAR_FQN = "akka.remote.rcl.MsgWithCycleMultiJvmSpec$Node2Bar"

  // cycles
 case class Node2Baz(@BeanProperty val bar: Node2Bar, value:String) {
    override def toString = null
    override def equals(p1: Any) = false
  }
 case class Node2Bar(@BeanProperty var baz:Node2Baz, value:String)  {
   override def toString = null
   override def equals(p1: Any) = false
 }

  class VerifyActor extends Actor with Serializable with MustMatchers {
    def receive = {
      case msg:AnyRef => {
        try {
          // class name must match
          msg.getClass.getName must equal(NODE2_BAZ_FQN)
          val bar = method("getBar").withReturnType(classOf[Object]).in(msg).invoke()
          msg must be(method("getBaz").withReturnType(classOf[Object]).in(bar).invoke())
          sender ! msg
        } catch {
          case e => {
            e.printStackTrace()
            sender ! e
          }
        }

      }
      case _ => // drop
    }
  }

  import com.typesafe.config.ConfigFactory

  override def commonConfig = ConfigFactory.parseString( """
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-verify.remote = %s
        }
      }
      remote {
        transport = "akka.remote.netty.rcl.BlockingRemoteClassLoaderTransport"
      }
    }""" format akkaURIs(1))
}

import MsgWithCycleMultiJvmSpec._

class MsgWithCycleMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {

  import MsgWithCycleMultiJvmSpec._

  val nodes = NrOfNodes

  // make sure Node1 classes are unavailable at Node2
  System.setProperty("path_hole.filter", "*Node2*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RclBlockingClassLoader")

  "___" must {
    "___" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_BAZ_FQN)
      }
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_BAR_FQN)
      }
      barrier("start")
      barrier("done")
    }
  }

}

class MsgWithCycleMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) {

  import MsgWithCycleMultiJvmSpec._

  val nodes = NrOfNodes

  import akka.util.duration._
  implicit val timeout: akka.util.Timeout = 5 seconds

  "Sending a message from Node1 to Node2" must {

    "work even if the message class is not available on Node2" in {
      barrier("start")

      val verifyActor = system.actorOf(Props[VerifyActor], "service-verify")

      val bar = new Node2Bar(null,"Bar")
      val baz = new Node2Baz(bar,"Baz");
      bar.baz = baz

      // first the easy part just make sure if what we send is properly serialized back
      val backBaz = Await.result(verifyActor ? baz, timeout.duration).asInstanceOf[Node2Baz]

      backBaz.value must equal(baz.value)
      backBaz.bar.value must equal(baz.bar.value)

      // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(verifyActor)
      barrier("done")
    }
  }

}