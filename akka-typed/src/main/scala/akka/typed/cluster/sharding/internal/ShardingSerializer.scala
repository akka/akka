/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.sharding.internal

import akka.typed.cluster.sharding.internal.protobuf.ShardingMessages
import java.nio.charset.StandardCharsets

import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import akka.typed.ActorRef
import akka.typed.cluster.ActorRefResolver
import akka.typed.internal.adapter.ActorRefAdapter
import akka.typed.scaladsl.adapter._
import akka.remote.serialization.WrappedPayloadSupport
import akka.typed.cluster.sharding.ShardingEnvelope
import java.io.NotSerializableException
import akka.typed.cluster.sharding.StartEntity

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ShardingSerializer(val system: akka.actor.ExtendedActorSystem)
  extends SerializerWithStringManifest with BaseSerializer {

  private val payloadSupport = new WrappedPayloadSupport(system)

  private val ShardingEnvelopeManifest = "a"

  override def manifest(o: AnyRef): String = o match {
    case ref: ShardingEnvelope[_] ⇒ ShardingEnvelopeManifest
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case env: ShardingEnvelope[_] ⇒
      val builder = ShardingMessages.ShardingEnvelope.newBuilder()
      builder.setEntityId(env.entityId)
      // special null for StartEntity, might change with issue #23679
      if (env.message != null)
        builder.setMessage(payloadSupport.payloadBuilder(env.message))
      builder.build().toByteArray()

    case _ ⇒
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case ShardingEnvelopeManifest ⇒
      val env = ShardingMessages.ShardingEnvelope.parseFrom(bytes)
      val entityId = env.getEntityId
      if (env.hasMessage) {
        val wrappedMsg = payloadSupport.deserializePayload(env.getMessage)
        ShardingEnvelope(entityId, wrappedMsg)
      } else {
        // special for StartEntity, might change with issue #23679
        StartEntity(entityId)
      }
    case _ ⇒
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

}
