/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.cluster.sharding.typed.internal.ShardingSerializer
import akka.serialization.SerializationExtension
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class ShardingSerializerSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "The typed ShardingSerializer" must {

    val serialization = SerializationExtension(ActorSystemAdapter.toUntyped(system))

    def checkSerialization(obj: AnyRef): Unit = {
      serialization.findSerializerFor(obj) match {
        case serializer: ShardingSerializer ⇒
          val blob = serializer.toBinary(obj)
          val ref = serializer.fromBinary(blob, serializer.manifest(obj))
          ref should ===(obj)
        case s ⇒
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
