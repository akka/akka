/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import java.nio.ByteBuffer

import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.util.Random
import java.nio.ByteOrder
import akka.serialization.ByteBufferSerializer
import akka.serialization.Serializer

object PrimitivesSerializationSpec {
  val serializationTestOverrides =
    """
    """

  val testConfig = ConfigFactory.parseString(serializationTestOverrides).withFallback(AkkaSpec.testConf)
}

class PrimitivesSerializationSpec extends AkkaSpec(PrimitivesSerializationSpec.testConfig) {

  val buffer = {
    val b = ByteBuffer.allocate(1024)
    b.order(ByteOrder.LITTLE_ENDIAN)
    b
  }

  val serialization = SerializationExtension(system)

  def verifySerialization(msg: AnyRef): Unit = {
    val serializer = serialization.serializerFor(msg.getClass)
    serializer.fromBinary(serializer.toBinary(msg), None) should ===(msg)
  }

  def verifySerializationByteBuffer(msg: AnyRef): Unit = {
    val serializer = serialization.serializerFor(msg.getClass).asInstanceOf[Serializer with ByteBufferSerializer]
    buffer.clear()
    serializer.toBinary(msg, buffer)
    buffer.flip()

    // also make sure that the Array and ByteBuffer formats are equal, given LITTLE_ENDIAN
    val array1 = new Array[Byte](buffer.remaining())
    buffer.get(array1)
    val array2 = serializer.toBinary(msg)
    ByteString(array1) should ===(ByteString(array2))

    buffer.rewind()
    serializer.fromBinary(buffer, "") should ===(msg)
  }

  "LongSerializer" must {
    Seq(0L, 1L, -1L, Long.MinValue, Long.MinValue + 1L, Long.MaxValue, Long.MaxValue - 1L)
      .map(_.asInstanceOf[AnyRef])
      .foreach { item =>
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

  }

  "IntSerializer" must {
    Seq(0, 1, -1, Int.MinValue, Int.MinValue + 1, Int.MaxValue, Int.MaxValue - 1).map(_.asInstanceOf[AnyRef]).foreach {
      item =>
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
  }

  "StringSerializer" must {
    val random = Random.nextString(256)
    Seq("empty string" -> "", "hello" -> "hello", "árvíztűrőütvefúrógép" -> "árvíztűrőütvefúrógép", "random" -> random)
      .foreach {
        case (scenario, item) =>
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

  }

  "ByteStringSerializer" must {
    Seq(
      "empty string" -> ByteString.empty,
      "simple content" -> ByteString("hello"),
      "concatenated content" -> (ByteString("hello") ++ ByteString("world")),
      "sliced content" -> ByteString("helloabc").take(5)).foreach {
      case (scenario, item) =>
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

  }

}
