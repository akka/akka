/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization

import java.nio.ByteBuffer
import java.nio.ByteOrder

import akka.testkit.AkkaSpec
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

object PrimitivesSerializationSpec {
  val serializationTestOverrides = ""

  val testConfig = ConfigFactory.parseString(serializationTestOverrides).withFallback(AkkaSpec.testConf)
}

class PrimitivesSerializationSpec extends AkkaSpec(PrimitivesSerializationSpec.testConfig) {

  val buffer = {
    val b = ByteBuffer.allocate(4096)
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

  "Boolean" must {
    // TODO issue #27330: BooleanSerializer not enabled for serialization in 2.5.x yet
    pending

    Seq(false, true, java.lang.Boolean.FALSE, java.lang.Boolean.TRUE).map(_.asInstanceOf[AnyRef]).zipWithIndex.foreach {
      case (item, i) =>
        s"resolve serializer for value $item ($i)" in {
          serialization.serializerFor(item.getClass).getClass should ===(classOf[BooleanSerializer])
        }

        s"serialize and de-serialize value $item  ($i)" in {
          verifySerialization(item)
        }

        s"serialize and de-serialize value $item ($i) using ByteBuffers" in {
          verifySerializationByteBuffer(item)
        }
    }

    "have right serializer id  ($i)" in {
      serialization.serializerFor(true.asInstanceOf[AnyRef].getClass).identifier === 35
    }
  }

}
