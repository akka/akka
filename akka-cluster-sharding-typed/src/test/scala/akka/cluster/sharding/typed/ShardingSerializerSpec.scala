/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding.typed

import akka.cluster.sharding.typed.internal.ShardingSerializer
import akka.serialization.SerializationExtension
import akka.actor.typed.TypedSpec
import akka.cluster.sharding.typed.internal.ShardingSerializer
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.AskPattern._

class ShardingSerializerSpec extends TypedSpec {

  object `The typed ShardingSerializer` {

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

    def `must serialize and deserialize ShardingEnvelope`(): Unit = {
      checkSerialization(ShardingEnvelope("abc", 42))
    }

    def `must serialize and deserialize StartEntity`(): Unit = {
      checkSerialization(StartEntity("abc"))
    }
  }

}
