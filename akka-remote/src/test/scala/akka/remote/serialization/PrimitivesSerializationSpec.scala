/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.serialization

import java.nio.ByteBuffer

import akka.actor.{ ActorIdentity, ExtendedActorSystem, Identify }
import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.util.Random

object PrimitivesSerializationSpec {
  val serializationTestOverrides =
    """
    akka.actor.enable-additional-serialization-bindings=on
    # or they can be enabled with
    # akka.remote.artery.enabled=on
    """

  val testConfig = ConfigFactory.parseString(serializationTestOverrides).withFallback(AkkaSpec.testConf)
}

class PrimitivesSerializationSpec extends AkkaSpec(PrimitivesSerializationSpec.testConfig) {

  val buffer = ByteBuffer.allocate(1024)

  "LongSerializer" must {
    Seq(0L, 1L, -1L, Long.MinValue, Long.MinValue + 1L, Long.MaxValue, Long.MaxValue - 1L).map(_.asInstanceOf[AnyRef]).foreach {
      item ⇒
        s"resolve serializer for value $item" in {
          val serializer = SerializationExtension(system)
          serializer.serializerFor(item.getClass).getClass should ===(classOf[LongSerializer])
        }

        s"serialize and de-serialize value $item" in {
          verifySerialization(item)
        }

        s"serialize and de-serialize value $item using ByteBuffers" in {
          verifySerializationByteBuffer(item)
        }
    }

    def verifySerialization(msg: AnyRef): Unit = {
      val serializer = new LongSerializer(system.asInstanceOf[ExtendedActorSystem])
      serializer.fromBinary(serializer.toBinary(msg), None) should ===(msg)
    }

    def verifySerializationByteBuffer(msg: AnyRef): Unit = {
      val serializer = new LongSerializer(system.asInstanceOf[ExtendedActorSystem])
      buffer.clear()
      serializer.toBinary(msg, buffer)
      buffer.flip()
      serializer.fromBinary(buffer, "") should ===(msg)
    }
  }

  "IntSerializer" must {
    Seq(0, 1, -1, Int.MinValue, Int.MinValue + 1, Int.MaxValue, Int.MaxValue - 1).map(_.asInstanceOf[AnyRef]).foreach {
      item ⇒
        s"resolve serializer for value $item" in {
          val serializer = SerializationExtension(system)
          serializer.serializerFor(item.getClass).getClass should ===(classOf[IntSerializer])
        }

        s"serialize and de-serialize value $item" in {
          verifySerialization(item)
        }

        s"serialize and de-serialize value $item using ByteBuffers" in {
          verifySerializationByteBuffer(item)
        }
    }

    def verifySerialization(msg: AnyRef): Unit = {
      val serializer = new IntSerializer(system.asInstanceOf[ExtendedActorSystem])
      serializer.fromBinary(serializer.toBinary(msg), None) should ===(msg)
    }

    def verifySerializationByteBuffer(msg: AnyRef): Unit = {
      val serializer = new IntSerializer(system.asInstanceOf[ExtendedActorSystem])
      buffer.clear()
      serializer.toBinary(msg, buffer)
      buffer.flip()
      serializer.fromBinary(buffer, "") should ===(msg)
    }
  }

  "StringSerializer" must {
    val random = Random.nextString(256)
    Seq(
      "empty string" → "",
      "hello" → "hello",
      "árvíztűrőütvefúrógép" → "árvíztűrőütvefúrógép",
      "random" → random
    ).foreach {
        case (scenario, item) ⇒
          s"resolve serializer for [$scenario]" in {
            val serializer = SerializationExtension(system)
            serializer.serializerFor(item.getClass).getClass should ===(classOf[StringSerializer])
          }

          s"serialize and de-serialize [$scenario]" in {
            verifySerialization(item)
          }

          s"serialize and de-serialize value [$scenario] using ByteBuffers" in {
            verifySerializationByteBuffer(item)
          }
      }

    def verifySerialization(msg: AnyRef): Unit = {
      val serializer = new StringSerializer(system.asInstanceOf[ExtendedActorSystem])
      serializer.fromBinary(serializer.toBinary(msg), None) should ===(msg)
    }

    def verifySerializationByteBuffer(msg: AnyRef): Unit = {
      val serializer = new StringSerializer(system.asInstanceOf[ExtendedActorSystem])
      buffer.clear()
      serializer.toBinary(msg, buffer)
      buffer.flip()
      serializer.fromBinary(buffer, "") should ===(msg)
    }
  }

  "ByteStringSerializer" must {
    Seq(
      "empty string" → ByteString.empty,
      "simple content" → ByteString("hello"),
      "concatenated content" → (ByteString("hello") ++ ByteString("world")),
      "sliced content" → ByteString("helloabc").take(5)
    ).foreach {
        case (scenario, item) ⇒
          s"resolve serializer for [$scenario]" in {
            val serializer = SerializationExtension(system)
            serializer.serializerFor(item.getClass).getClass should ===(classOf[ByteStringSerializer])
          }

          s"serialize and de-serialize [$scenario]" in {
            verifySerialization(item)
          }

          s"serialize and de-serialize value [$scenario] using ByteBuffers" in {
            verifySerializationByteBuffer(item)
          }
      }

    def verifySerialization(msg: AnyRef): Unit = {
      val serializer = new ByteStringSerializer(system.asInstanceOf[ExtendedActorSystem])
      serializer.fromBinary(serializer.toBinary(msg), None) should ===(msg)
    }

    def verifySerializationByteBuffer(msg: AnyRef): Unit = {
      val serializer = new ByteStringSerializer(system.asInstanceOf[ExtendedActorSystem])
      buffer.clear()
      serializer.toBinary(msg, buffer)
      buffer.flip()
      serializer.fromBinary(buffer, "") should ===(msg)
    }
  }

}
