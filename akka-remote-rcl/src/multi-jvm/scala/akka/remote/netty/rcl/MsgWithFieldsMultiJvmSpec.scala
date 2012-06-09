package akka.remote.netty.rcl

import akka.actor.{Actor, Props}
import akka.dispatch.Await
import akka.pattern.ask
import akka.remote.{AbstractRemoteActorMultiJvmSpec, AkkaRemoteSpec}
import org.scalatest.matchers.MustMatchers

object MsgWithFieldsMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  case class Node2ValueObject(r: Int, g: Int, b: Int)
  case class Node2MsgWithFields(foo: String, baz: Node2ValueObject)

  // pretty lame to have to do it this way but other-wize we'd load the class we are trying to load via RCL
  val  NODE2_VALUE_OBJECT_FQN = "akka.remote.netty.rcl.MsgWithFieldsMultiJvmSpec$Node2ValueObject"
  val  NODE2_MSG_WITH_FIELDS_FQN = "akka.remote.netty.rcl.MsgWithFieldsMultiJvmSpec$Node2MsgWithFields"

  class VerifyActor extends Actor with Serializable with MustMatchers {
    def receive = {
      case msg: AnyRef => {
        try {
          // class name must match
          msg.getClass.getName must equal(NODE2_MSG_WITH_FIELDS_FQN)
          // the field values must match
          ReflectionUtil.getFieldValue("foo", msg)  must equal("a")
          // the same CL that loaded the MSG should load the value object
          val innerValue = ReflectionUtil.getFieldValue("baz", msg).asInstanceOf[AnyRef]
          innerValue.getClass.getClassLoader must be(msg.getClass.getClassLoader)
          // inner value fields must match

          ReflectionUtil.getFieldValue("r", innerValue)  must equal(0)
          ReflectionUtil.getFieldValue("g", innerValue)  must equal(122)
          ReflectionUtil.getFieldValue("b", innerValue)  must equal(255)
          sender ! msg
        } catch {
          case e => {
            e.printStackTrace()
            sender ! e
          }
        }

      }

      case _ => sender ! Right(new IllegalArgumentException("Expected AnyRef."))
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
        transport = "akka.remote.netty.rcl.RemoteClassLoadingTransport"
        netty.message-frame-size = 10 MiB
      }
    }""" format akkaURIs(1))
}

import MsgWithFieldsMultiJvmSpec._

class MsgWithFieldsMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {

  import MsgWithFieldsMultiJvmSpec._

  System.setProperty("path_hole.filter", "*Node2*")
  System.setProperty("path_hole.unfiltered.cls", "akka.remote.netty.rcl.RemoteClassLoader")

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_VALUE_OBJECT_FQN)
      }
      intercept[ClassNotFoundException] {
        Class.forName(NODE2_MSG_WITH_FIELDS_FQN)
      }
      barrier("start")
      barrier("done")
    }
  }
}

class MsgWithFieldsMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) {

  import MsgWithFieldsMultiJvmSpec._
  val nodes = NrOfNodes

  import akka.util.duration._
  implicit val timeout: akka.util.Timeout = 15 seconds

  "Sending a message from Node1 to Node2" must {

    "work even if the message class is not available on Node2" in {
      barrier("start")
      val verifyActor = system.actorOf(Props[VerifyActor], "service-verify")
      val msg = Node2MsgWithFields("a", Node2ValueObject(0, 122, 255))
      // first the easy part just make sure if what we send is properly serialized back
      // we make sure stuff looks on the remote node as it looks on our node
      Await.result(verifyActor ? msg, timeout.duration).asInstanceOf[Node2MsgWithFields] must equal(msg)
      system.stop(verifyActor)
      barrier("done")
    }
  }

}