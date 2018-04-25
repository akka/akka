/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.io.NotSerializableException

import akka.actor.{ Actor, ActorSystem, ExtendedActorSystem, Props, RootActorPath }
import akka.serialization.SerializerWithStringManifest
import akka.testkit.AkkaSpec
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.duration._

object TransientSerializationErrorSpec {
  object ManifestNotSerializable
  object ManifestIllegal
  object ToBinaryNotSerializable
  object ToBinaryIllegal
  object NotDeserializable
  object IllegalOnDeserialize

  class TestSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
    def identifier: Int = 666
    def manifest(o: AnyRef): String = o match {
      case ManifestNotSerializable ⇒ throw new NotSerializableException()
      case ManifestIllegal         ⇒ throw new IllegalArgumentException()
      case ToBinaryNotSerializable ⇒ "TBNS"
      case ToBinaryIllegal         ⇒ "TI"
      case NotDeserializable       ⇒ "ND"
      case IllegalOnDeserialize    ⇒ "IOD"
    }
    def toBinary(o: AnyRef): Array[Byte] = o match {
      case ToBinaryNotSerializable ⇒ throw new NotSerializableException()
      case ToBinaryIllegal         ⇒ throw new IllegalArgumentException()
      case _                       ⇒ Array.emptyByteArray
    }
    def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
      manifest match {
        case "ND"  ⇒ throw new NotSerializableException() // Not sure this applies here
        case "IOD" ⇒ throw new IllegalArgumentException()
      }
    }
  }
}

abstract class AbstractTransientSerializationErrorSpec(config: Config) extends AkkaSpec(
  config.withFallback(ConfigFactory.parseString(
    """
    akka {
      loglevel = info
      actor {
        provider = remote
        warn-about-java-serializer-usage = off
        serialize-creators = off
        serializers {
          test = "akka.remote.TransientSerializationErrorSpec$TestSerializer"
        }
        serialization-bindings {
          "akka.remote.TransientSerializationErrorSpec$ManifestNotSerializable$" = test
          "akka.remote.TransientSerializationErrorSpec$ManifestIllegal$" = test
          "akka.remote.TransientSerializationErrorSpec$ToBinaryNotSerializable$" = test
          "akka.remote.TransientSerializationErrorSpec$ToBinaryIllegal$" = test
          "akka.remote.TransientSerializationErrorSpec$NotDeserializable$" = test
          "akka.remote.TransientSerializationErrorSpec$IllegalOnDeserialize$" = test
        }
      }
    }
  """))) {

  import TransientSerializationErrorSpec._

  val port = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress.port.get
  val sysName = system.name
  val protocol =
    if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka"
    else "akka.tcp"

  val system2 = ActorSystem(system.name, system.settings.config) // TODO port?
  val system2Address = RARP(system2).provider.getDefaultAddress

  class EchoActor extends Actor {
    def receive = {
      case msg ⇒ sender() ! msg
    }
  }

  "The transport" must {

    "stay alive after a transient exception from the serializer" in {
      system2.actorOf(Props(new EchoActor), "echo")

      val selection = system.actorSelection(RootActorPath(system2Address) / "user" / "echo")

      awaitAssert(
        {
          selection.tell("ping", this.testActor)
          expectMsg("ping")
        },
        5.seconds)

      // none of these should tear down the connection
      List(
        ManifestIllegal,
        ManifestNotSerializable,
        ToBinaryIllegal,
        ToBinaryNotSerializable,
        NotDeserializable,
        IllegalOnDeserialize
      ).foreach(msg ⇒
        selection.tell(msg, this.testActor)
      )

      // make sure we still have a connection
      awaitAssert(
        {
          selection.tell("ping", this.testActor)
          expectMsg("ping")
        },
        5.seconds)

    }
  }
}

class TransientSerializationErrorSpec extends AbstractTransientSerializationErrorSpec(ConfigFactory.parseString("""
  akka.remote.netty.tcp {
    hostname = localhost
    port = 0
 }
"""))
