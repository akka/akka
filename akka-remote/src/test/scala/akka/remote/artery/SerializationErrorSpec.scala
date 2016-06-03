/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import akka.actor.{ ActorIdentity, ActorSystem, ExtendedActorSystem, Identify, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.testkit.TestActors
import com.typesafe.config.ConfigFactory
import akka.testkit.EventFilter

object SerializationErrorSpec {

  val config = ConfigFactory.parseString(s"""
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = 0
       actor {
         serialize-creators = false
         serialize-messages = false
       }
     }
  """)

  object NotSerializableMsg

}

class SerializationErrorSpec extends AkkaSpec(SerializationErrorSpec.config) with ImplicitSender {
  import SerializationErrorSpec._

  val configB = ConfigFactory.parseString("""
     akka.actor.serialization-identifiers {
       # this will cause deserialization error
       "akka.serialization.ByteArraySerializer" = -4
     }
     """).withFallback(system.settings.config)
  val systemB = ActorSystem("systemB", configB)
  systemB.actorOf(TestActors.echoActorProps, "echo")
  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val rootB = RootActorPath(addressB)

  override def afterTermination(): Unit = shutdown(systemB)

  "Serialization error" must {

    "be logged when serialize fails" in {
      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      remoteRef ! "ping"
      expectMsg("ping")

      EventFilter[java.io.NotSerializableException](start = "Failed to serialize message", occurrences = 1).intercept {
        remoteRef ! NotSerializableMsg
      }

      remoteRef ! "ping2"
      expectMsg("ping2")
    }

    "be logged when deserialize fails" in {
      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      remoteRef ! "ping"
      expectMsg("ping")

      EventFilter.warning(
        start = "Failed to deserialize message with serializer id [4]", occurrences = 1).intercept {
        remoteRef ! "boom".getBytes("utf-8")
      }(systemB)

      remoteRef ! "ping2"
      expectMsg("ping2")
    }

  }

}
