/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.remote.ContainerFormats
import akka.serialization.{ SerializationExtension, Serializer }
import akka.protobuf.ByteString

/**
 * INTERNAL API
 */
private[akka] class WrappedPayloadSupport(system: ExtendedActorSystem) {

  private lazy val serialization = SerializationExtension(system)

  def payloadBuilder(input: Any): ContainerFormats.Payload.Builder = {
    val payload = input.asInstanceOf[AnyRef]
    val builder = ContainerFormats.Payload.newBuilder()
    val serializer = serialization.findSerializerFor(payload)

    builder
      .setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(payload)))
      .setSerializerId(serializer.identifier)

    Serializer.manifestFor(serializer, payload)
      .filter(_.nonEmpty)
      .foreach(ms ⇒ builder.setMessageManifest(ByteString.copyFromUtf8(ms)))

    builder
  }

  def deserializePayload(payload: ContainerFormats.Payload): Any = {
    val manifest = if (payload.hasMessageManifest) payload.getMessageManifest.toStringUtf8 else ""
    serialization.deserialize(
      payload.getEnclosedMessage.toByteArray,
      payload.getSerializerId,
      manifest).get
  }

}
