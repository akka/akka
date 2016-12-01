/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor._
import akka.remote.{ MessageSerializer, RemoteWatcher }
import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

import scala.util.control.NoStackTrace
import java.util.Optional

object MiscMessageSerializerSpec {
  val serializationTestOverrides =
    """
    akka.actor.enable-additional-serialization-bindings=on
    # or they can be enabled with
    # akka.remote.artery.enabled=on

    akka.actor.serialization-bindings {
      "akka.remote.serialization.MiscMessageSerializerSpec$TestException" = akka-misc
    }
    """

  val testConfig = ConfigFactory.parseString(serializationTestOverrides).withFallback(AkkaSpec.testConf)

  class TestException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
    def this(msg: String) = this(msg, null)

    override def equals(other: Any): Boolean = other match {
      case e: TestException ⇒
        e.getMessage == getMessage && e.stackTrace == stackTrace && e.getCause == getCause
      case _ ⇒ false
    }

    def stackTrace: List[StackTraceElement] =
      if (getStackTrace == null) Nil
      else getStackTrace.toList
  }

  class TestExceptionNoStack(msg: String) extends TestException(msg) with NoStackTrace {
    override def equals(other: Any): Boolean = other match {
      case e: TestExceptionNoStack ⇒
        e.getMessage == getMessage && e.stackTrace == stackTrace
      case _ ⇒ false
    }
  }

  class OtherException(msg: String) extends IllegalArgumentException(msg) {
    override def equals(other: Any): Boolean = other match {
      case e: OtherException ⇒ e.getMessage == getMessage
      case _                 ⇒ false
    }
  }
}

class MiscMessageSerializerSpec extends AkkaSpec(MiscMessageSerializerSpec.testConfig) {
  import MiscMessageSerializerSpec._

  val ref = system.actorOf(Props.empty, "hello")

  "MiscMessageSerializer" must {
    Seq(
      "Identify" → Identify("some-message"),
      "Identify with None" → Identify(None),
      "Identify with Some" → Identify(Some("value")),
      "ActorIdentity without actor ref" → ActorIdentity("some-message", ref = None),
      "ActorIdentity with actor ref" → ActorIdentity("some-message", ref = Some(testActor)),
      "TestException" → new TestException("err"),
      "TestExceptionNoStack" → new TestExceptionNoStack("err2"),
      "TestException with cause" → new TestException("err3", new TestException("cause")),
      "Status.Success" → Status.Success("value"),
      "Status.Failure" → Status.Failure(new TestException("err")),
      "Status.Failure JavaSer" → Status.Failure(new OtherException("exc")), // exc with JavaSerializer
      "ActorRef" → ref,
      "Some" → Some("value"),
      "None" → None,
      "Optional.present" → Optional.of("value2"),
      "Optional.empty" → Optional.empty(),
      "Kill" → Kill,
      "PoisonPill" → PoisonPill,
      "RemoteWatcher.Heartbeat" → RemoteWatcher.Heartbeat,
      "RemoteWatcher.HertbeatRsp" → RemoteWatcher.HeartbeatRsp(65537)).foreach {
        case (scenario, item) ⇒
          s"resolve serializer for $scenario" in {
            val serializer = SerializationExtension(system)
            serializer.serializerFor(item.getClass).getClass should ===(classOf[MiscMessageSerializer])
          }

          s"serialize and de-serialize $scenario" in {
            verifySerialization(item)
          }
      }

    "reject invalid manifest" in {
      intercept[IllegalArgumentException] {
        val serializer = new MiscMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
        serializer.manifest("INVALID")
      }
    }

    "reject deserialization with invalid manifest" in {
      intercept[IllegalArgumentException] {
        val serializer = new MiscMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
        serializer.fromBinary(Array.empty[Byte], "INVALID")
      }
    }

    def verifySerialization(msg: AnyRef): Unit = {
      val serializer = new MiscMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
      serializer.fromBinary(serializer.toBinary(msg), serializer.manifest(msg)) should ===(msg)
    }

    // Separate tests due to missing equality on ActorInitializationException
    "resolve serializer for ActorInitializationException" in {
      val serializer = SerializationExtension(system)
      serializer.serializerFor(classOf[ActorInitializationException]).getClass should ===(classOf[MiscMessageSerializer])
    }

    "serialize and deserialze ActorInitializationException" in {
      val aiex = ActorInitializationException(ref, "test", new TestException("err"))
      val serializer = new MiscMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
      val deserialized = serializer.fromBinary(serializer.toBinary(aiex), serializer.manifest(aiex))
        .asInstanceOf[ActorInitializationException]

      deserialized.getCause should ===(aiex.getCause)
      deserialized.getMessage should ===(aiex.getMessage)
      deserialized.getActor should ===(aiex.getActor)
    }

    "serialize and deserialze ActorInitializationException if ref is null" in {
      val aiex = ActorInitializationException(null, "test", new TestException("err"))
      val serializer = new MiscMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
      val deserialized = serializer.fromBinary(serializer.toBinary(aiex), serializer.manifest(aiex))
        .asInstanceOf[ActorInitializationException]

      deserialized.getCause should ===(aiex.getCause)
      deserialized.getMessage should ===(aiex.getMessage)
      deserialized.getActor should ===(aiex.getActor)
    }

    "serialize and deserialze ActorInitializationException if cause  is null" in {
      val aiex = ActorInitializationException(ref, "test", null)
      val serializer = new MiscMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
      val deserialized = serializer.fromBinary(serializer.toBinary(aiex), serializer.manifest(aiex))
        .asInstanceOf[ActorInitializationException]

      deserialized.getCause should ===(aiex.getCause)
      deserialized.getMessage should ===(aiex.getMessage)
      deserialized.getActor should ===(aiex.getActor)
    }
  }
}

