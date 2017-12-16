/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.sharding

import akka.serialization.SerializationExtension
import akka.typed.TypedSpec
import akka.typed.cluster.sharding.internal.ShardingSerializer
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.AskPattern._

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
