/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.cluster.sharding.typed.internal.ShardingSerializer
import akka.serialization.SerializationExtension

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShardingSerializerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "The typed ShardingSerializer" must {

    val serialization = SerializationExtension(ActorSystemAdapter.toClassic(system))

    def checkSerialization(obj: AnyRef): Unit = {
      serialization.findSerializerFor(obj) match {
        case serializer: ShardingSerializer =>
          val blob = serializer.toBinary(obj)
          val ref = serializer.fromBinary(blob, serializer.manifest(obj))
          ref should ===(obj)

          val buffer = ByteBuffer.allocate(128)
          buffer.order(ByteOrder.LITTLE_ENDIAN)
          serializer.toBinary(obj, buffer)
          buffer.flip()
          val refFromBuf = serializer.fromBinary(buffer, serializer.manifest(obj))
          refFromBuf should ===(obj)
          buffer.clear()

        case s =>
          throw new IllegalStateException(s"Wrong serializer ${s.getClass} for ${obj.getClass}")
      }
    }

    "must serialize and deserialize ShardingEnvelope" in {
      checkSerialization(ShardingEnvelope("abc", 42))
    }

    "must serialize and deserialize StartEntity" in {
      checkSerialization(scaladsl.StartEntity[Int]("abc"))
      checkSerialization(javadsl.StartEntity.create(classOf[java.lang.Integer], "def"))
    }
  }
}
