/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor._
import akka.remote.MessageSerializer
import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

object MiscMessageSerializerSpec {
  val serializationTestOverrides =
    """
       |akka.actor.serialization-bindings = {
       |  "akka.actor.Identify" = akka-misc
       |  "akka.actor.ActorIdentity" = akka-misc
       |  "scala.Some" = akka-misc
       |  "scala.None$" = akka-misc
       |}
     """.stripMargin

  val testConfig = ConfigFactory.parseString(serializationTestOverrides).withFallback(AkkaSpec.testConf)
}

class MiscMessageSerializerSpec extends AkkaSpec(MiscMessageSerializerSpec.testConfig) {

  "MiscMessageSerializer" must {
    Seq(
      "Identify" → Identify("some-message"),
      "Identify with None" → Identify(None),
      "Identify with Some" → Identify(Some("value")),
      "ActorIdentity without actor ref" → ActorIdentity("some-message", ref = None),
      "ActorIdentity with actor ref" → ActorIdentity("some-message", ref = Some(testActor)),
      "Some" → Some("value"),
      "None" → None).foreach {
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
  }
}

