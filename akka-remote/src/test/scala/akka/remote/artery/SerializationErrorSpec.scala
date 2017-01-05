/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import akka.actor.{ ActorIdentity, ActorSystem, ExtendedActorSystem, Identify, RootActorPath }
import akka.remote.RARP
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.testkit.TestActors
import com.typesafe.config.ConfigFactory
import akka.testkit.EventFilter

object SerializationErrorSpec {

  object NotSerializableMsg

}

class SerializationErrorSpec extends ArteryMultiNodeSpec(ArterySpecSupport.defaultConfig) with ImplicitSender {
  import SerializationErrorSpec._

  val systemB = newRemoteSystem(
    name = Some("systemB"),
    extraConfig = Some("""
       akka.actor.serialization-identifiers {
         # this will cause deserialization error
         "akka.serialization.ByteArraySerializer" = -4
       }
       """))
  systemB.actorOf(TestActors.echoActorProps, "echo")
  val addressB = address(systemB)
  val rootB = RootActorPath(addressB)

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
