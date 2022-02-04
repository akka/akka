/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.io.NotSerializableException

import com.typesafe.config.{ Config, ConfigFactory }

import akka.actor.{ ActorSystem, ExtendedActorSystem, RootActorPath }
import akka.serialization.SerializerWithStringManifest
import akka.testkit.{ AkkaSpec, TestActors, TestKit }
import akka.util.unused

object TransientSerializationErrorSpec {
  object ManifestNotSerializable
  object ManifestIllegal
  object ToBinaryNotSerializable
  object ToBinaryIllegal
  object NotDeserializable
  object IllegalOnDeserialize

  class TestSerializer(@unused system: ExtendedActorSystem) extends SerializerWithStringManifest {
    def identifier: Int = 666
    def manifest(o: AnyRef): String = o match {
      case ManifestNotSerializable => throw new NotSerializableException()
      case ManifestIllegal         => throw new IllegalArgumentException()
      case ToBinaryNotSerializable => "TBNS"
      case ToBinaryIllegal         => "TI"
      case NotDeserializable       => "ND"
      case IllegalOnDeserialize    => "IOD"
      case _                       => throw new NotSerializableException()
    }
    def toBinary(o: AnyRef): Array[Byte] = o match {
      case ToBinaryNotSerializable => throw new NotSerializableException()
      case ToBinaryIllegal         => throw new IllegalArgumentException()
      case _                       => Array.emptyByteArray
    }
    def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
      manifest match {
        case "ND"  => throw new NotSerializableException() // Not sure this applies here
        case "IOD" => throw new IllegalArgumentException()
        case _     => throw new NotSerializableException()
      }
    }
  }
}

abstract class AbstractTransientSerializationErrorSpec(config: Config)
    extends AkkaSpec(
      config.withFallback(
        ConfigFactory.parseString("""
    akka {
      loglevel = info
      actor {
        provider = remote
        warn-about-java-serializer-usage = off
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

  val system2 = ActorSystem(system.name, system.settings.config)
  val system2Address = RARP(system2).provider.getDefaultAddress

  "The transport" must {

    "stay alive after a transient exception from the serializer" in {
      system2.actorOf(TestActors.echoActorProps, "echo")

      val selection = system.actorSelection(RootActorPath(system2Address) / "user" / "echo")

      selection.tell("ping", this.testActor)
      expectMsg("ping")

      // none of these should tear down the connection
      List(
        ManifestIllegal,
        ManifestNotSerializable,
        ToBinaryIllegal,
        ToBinaryNotSerializable,
        NotDeserializable,
        IllegalOnDeserialize).foreach(msg => selection.tell(msg, this.testActor))

      // make sure we still have a connection
      selection.tell("ping", this.testActor)
      expectMsg("ping")

    }
  }

  override def afterTermination(): Unit = {
    TestKit.shutdownActorSystem(system2)
  }
}

class TransientSerializationErrorSpec
    extends AbstractTransientSerializationErrorSpec(ConfigFactory.parseString("""
  akka.remote.artery.enabled = false 
  akka.remote.classic.netty.tcp {
    hostname = localhost
    port = 0
 }
"""))
