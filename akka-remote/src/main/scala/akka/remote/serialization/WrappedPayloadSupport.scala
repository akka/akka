/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.remote.ContainerFormats
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
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

    serializer match {
      case ser2: SerializerWithStringManifest ⇒
        val manifest = ser2.manifest(payload)
        if (manifest != "")
          builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
      case _ ⇒
        if (serializer.includeManifest)
          builder.setMessageManifest(ByteString.copyFromUtf8(payload.getClass.getName))
    }

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
