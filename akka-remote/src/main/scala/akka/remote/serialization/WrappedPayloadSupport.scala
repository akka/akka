/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.remote.ContainerFormats
import akka.serialization.{ SerializationExtension, Serializers }
import akka.protobufv3.internal.ByteString
import akka.serialization.DisabledJavaSerializer

/**
 * INTERNAL API
 */
private[akka] class WrappedPayloadSupport(system: ExtendedActorSystem) {

  private lazy val serialization = SerializationExtension(system)
  private val log = Logging(system, getClass)

  /**
   * Serialize the `input` along with its `manifest` and `serializerId`.
   *
   * If `input` is a `Throwable` and can't be serialized because Java serialization is disabled it
   * will fallback to `ThrowableNotSerializableException`.
   */
  def payloadBuilder(input: Any): ContainerFormats.Payload.Builder = {
    val payload = input.asInstanceOf[AnyRef]
    val builder = ContainerFormats.Payload.newBuilder()
    val serializer = serialization.findSerializerFor(payload)

    payload match {
      case t: Throwable if serializer.isInstanceOf[DisabledJavaSerializer] =>
        val notSerializableException =
          new ThrowableNotSerializableException(t.getMessage, t.getClass.getName, t.getCause)
        log.debug(
          "Couldn't serialize [{}] because Java serialization is disabled. Fallback to " +
          "ThrowableNotSerializableException. {}",
          notSerializableException.originalClassName,
          notSerializableException.originalMessage)
        val serializer2 = serialization.findSerializerFor(notSerializableException)
        builder
          .setEnclosedMessage(ByteString.copyFrom(serializer2.toBinary(notSerializableException)))
          .setSerializerId(serializer2.identifier)
        val manifest = Serializers.manifestFor(serializer2, notSerializableException)
        if (manifest.nonEmpty) builder.setMessageManifest(ByteString.copyFromUtf8(manifest))

      case _ =>
        builder
          .setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(payload)))
          .setSerializerId(serializer.identifier)
        val manifest = Serializers.manifestFor(serializer, payload)
        if (manifest.nonEmpty) builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
    }

    builder
  }

  def deserializePayload(payload: ContainerFormats.Payload): Any = {
    val manifest = if (payload.hasMessageManifest) payload.getMessageManifest.toStringUtf8 else ""
    serialization.deserialize(payload.getEnclosedMessage.toByteArray, payload.getSerializerId, manifest).get
  }

}
