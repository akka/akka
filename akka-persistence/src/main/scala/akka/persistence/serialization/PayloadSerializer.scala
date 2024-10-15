/*
 * Copyright (C) 2023-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.serialization

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.persistence.FilteredPayload
import akka.persistence.SerializedEvent
import akka.serialization.BaseSerializer
import akka.serialization.SerializerWithStringManifest
import akka.persistence.serialization.{ MessageFormats => mf }
import akka.protobufv3.internal.ByteString
import akka.protobufv3.internal.UnsafeByteOperations

/**
 * INTERNAL API
 */
@InternalApi
final class PayloadSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  private val FilteredPayloadManifest = "F"
  private val SerializedEventManifest = "S"

  override def manifest(o: AnyRef): String = o match {
    case FilteredPayload    => FilteredPayloadManifest
    case _: SerializedEvent => SerializedEventManifest
    case _                  => throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case FilteredPayload     => Array.empty
    case se: SerializedEvent => payloadBuilder(se).build.toByteArray
    case _                   => throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  private def payloadBuilder(se: SerializedEvent): mf.PersistentPayload.Builder = {
    val builder = mf.PersistentPayload.newBuilder()
    builder.setPayload(UnsafeByteOperations.unsafeWrap(se.bytes))
    builder.setSerializerId(se.serializerId)
    if (se.serializerManifest.nonEmpty) builder.setPayloadManifest(ByteString.copyFromUtf8(se.serializerManifest))
    builder
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case FilteredPayloadManifest => FilteredPayload
    case SerializedEventManifest => serializedEvent(mf.PersistentPayload.parseFrom(bytes))
    case _                       => throw new IllegalArgumentException(s"Unknown manifest [$manifest]")
  }

  private def serializedEvent(persistentPayload: mf.PersistentPayload): SerializedEvent = {
    val manifest =
      if (persistentPayload.hasPayloadManifest)
        persistentPayload.getPayloadManifest.toStringUtf8
      else ""

    new SerializedEvent(persistentPayload.getPayload.toByteArray, persistentPayload.getSerializerId, manifest)
  }

}
