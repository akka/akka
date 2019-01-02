/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor.setup.ActorSystemSetup
import akka.actor.{ ActorSystem, BootstrapSetup, ExtendedActorSystem }
import akka.serialization._
import akka.testkit.{ AkkaSpec, TestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import akka.actor.actorRef2Scala
import java.util.{ BitSet ⇒ ProgrammaticJavaDummy }
import java.util.{ Date ⇒ SerializableDummy }

class ConfigurationDummy
class ProgrammaticDummy

object AllowJavaSerializationOffSpec {

  val dummySerializer = new FakeSerializer

  val serializationSettings = SerializationSetup { _ ⇒
    List(
      SerializerDetails("test", dummySerializer, List(classOf[ProgrammaticDummy])))
  }
  val bootstrapSettings = BootstrapSetup(None, Some(ConfigFactory.parseString("""
    akka {
      actor {
        serialize-messages = off
        # this is by default on, but tests are running with off, use defaults here
        warn-about-java-serializer-usage = on

        serialization-bindings {
          "akka.serialization.ConfigurationDummy" = test
        }
      }
    }
    """)), None)
  val actorSystemSettings = ActorSystemSetup(bootstrapSettings, serializationSettings)

  val noJavaSerializationSystem = ActorSystem("AllowJavaSerializationOffSpec" + "NoJavaSerialization", ConfigFactory.parseString(
    """
    akka {
      actor {
        allow-java-serialization = off
        # this is by default on, but tests are running with off, use defaults here
        warn-about-java-serializer-usage = on
      }
    }
    """.stripMargin))
  val noJavaSerializer = new DisabledJavaSerializer(noJavaSerializationSystem.asInstanceOf[ExtendedActorSystem])

}

class AllowJavaSerializationOffSpec extends AkkaSpec(
  ActorSystem("AllowJavaSerializationOffSpec", AllowJavaSerializationOffSpec.actorSystemSettings)) {

  import AllowJavaSerializationOffSpec._

  // This is a weird edge case, someone creating a JavaSerializer manually and using it in a system means
  // that they'd need a different actor system to be able to create it... someone MAY pick a system with
  // allow-java-serialization=on to create the SerializationSetup and use that SerializationSetup
  // in another system with allow-java-serialization=off
  val addedJavaSerializationSettings = SerializationSetup { _ ⇒
    List(
      SerializerDetails("test", dummySerializer, List(classOf[ProgrammaticDummy])),
      SerializerDetails("java-manual", new JavaSerializer(system.asInstanceOf[ExtendedActorSystem]), List(classOf[ProgrammaticJavaDummy])))
  }
  val addedJavaSerializationProgramaticallyButDisabledSettings = BootstrapSetup(None, Some(ConfigFactory.parseString("""
    akka {
      loglevel = debug
      actor {
        enable-additional-serialization-bindings = off # this should be overridden by the setting below, which should force it to be on
        allow-java-serialization = off
        # this is by default on, but tests are running with off, use defaults here
        warn-about-java-serializer-usage = on
      }
    }
    """)), None)

  val dontAllowJavaSystem =
    ActorSystem("addedJavaSerializationSystem", ActorSystemSetup(addedJavaSerializationProgramaticallyButDisabledSettings, addedJavaSerializationSettings))

  "Disabling java serialization" should {

    "throw if passed system to JavaSerializer has allow-java-serialization = off" in {
      intercept[DisabledJavaSerializer.JavaSerializationException] {
        new JavaSerializer(noJavaSerializationSystem.asInstanceOf[ExtendedActorSystem])
      }.getMessage should include("akka.actor.allow-java-serialization = off")

      intercept[DisabledJavaSerializer.JavaSerializationException] {
        SerializationExtension(dontAllowJavaSystem).findSerializerFor(new ProgrammaticJavaDummy).toBinary(new ProgrammaticJavaDummy)
      }
    }

    "enable additional-serialization-bindings" in {
      val some = Some("foo")
      val ser = SerializationExtension(dontAllowJavaSystem).findSerializerFor(some).asInstanceOf[MiscMessageSerializer]
      val bytes = ser.toBinary(some)
      ser.fromBinary(bytes, ser.manifest(some)) should ===(Some("foo"))
      SerializationExtension(dontAllowJavaSystem).deserialize(bytes, ser.identifier, ser.manifest(some))
        .get should ===(Some("foo"))
    }

    "have replaced java serializer" in {
      val p = TestProbe()(dontAllowJavaSystem) // only receiver has the serialization disabled

      p.ref ! new ProgrammaticJavaDummy
      SerializationExtension(system).findSerializerFor(new ProgrammaticJavaDummy).toBinary(new ProgrammaticJavaDummy)
      // should not receive this one, it would have been java serialization!
      p.expectNoMsg(100.millis)

      p.ref ! new ProgrammaticDummy
      p.expectMsgType[ProgrammaticDummy]
    }

    "disable java serialization also for incoming messages if serializer id usually would have found the serializer" in {
      val ser1 = SerializationExtension(system)
      val msg = new SerializableDummy
      val bytes = ser1.serialize(msg).get
      val serId = ser1.findSerializerFor(msg).identifier
      ser1.findSerializerFor(msg).includeManifest should ===(false)

      val ser2 = SerializationExtension(noJavaSerializationSystem)
      ser2.findSerializerFor(new SerializableDummy) should ===(noJavaSerializer)
      ser2.serializerByIdentity(serId) should ===(noJavaSerializer)
      intercept[DisabledJavaSerializer.JavaSerializationException] {
        ser2.deserialize(bytes, serId, "").get
      }
    }
  }

  override def afterTermination(): Unit = {
    TestKit.shutdownActorSystem(noJavaSerializationSystem)
    TestKit.shutdownActorSystem(dontAllowJavaSystem)
  }

}
