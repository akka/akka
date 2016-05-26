/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import akka.remote.WireFormats._
import akka.protobuf.ByteString
import akka.actor.ExtendedActorSystem
import akka.remote.artery.{ EnvelopeBuffer, HeaderBuilder }
import akka.serialization.{ Serialization, SerializationExtension, SerializerWithStringManifest }
import akka.serialization.ByteBufferSerializer

/**
 * INTERNAL API
 *
 * MessageSerializer is a helper for serializing and deserialize messages
 */
private[akka] object MessageSerializer {

  /**
   * Uses Akka Serialization for the specified ActorSystem to transform the given MessageProtocol to a message
   */
  def deserialize(system: ExtendedActorSystem, messageProtocol: SerializedMessage): AnyRef = {
    SerializationExtension(system).deserialize(
      messageProtocol.getMessage.toByteArray,
      messageProtocol.getSerializerId,
      if (messageProtocol.hasMessageManifest) messageProtocol.getMessageManifest.toStringUtf8 else "").get
  }

  /**
   * Uses Akka Serialization for the specified ActorSystem to transform the given message to a MessageProtocol
   */
  def serialize(system: ExtendedActorSystem, message: AnyRef): SerializedMessage = {
    val s = SerializationExtension(system)
    val serializer = s.findSerializerFor(message)
    val builder = SerializedMessage.newBuilder
    builder.setMessage(ByteString.copyFrom(serializer.toBinary(message)))
    builder.setSerializerId(serializer.identifier)
    serializer match {
      case ser2: SerializerWithStringManifest ⇒
        val manifest = ser2.manifest(message)
        if (manifest != "")
          builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
      case _ ⇒
        if (serializer.includeManifest)
          builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
    }
    builder.build
  }

  def serializeForArtery(serialization: Serialization, message: AnyRef, headerBuilder: HeaderBuilder, envelope: EnvelopeBuffer): Unit = {
    val serializer = serialization.findSerializerFor(message)

    // FIXME: This should be a FQCN instead
    headerBuilder.serializer = serializer.identifier.toString

    def manifest: String = serializer match {
      case ser: SerializerWithStringManifest ⇒ ser.manifest(message)
      case _                                 ⇒ if (serializer.includeManifest) message.getClass.getName else ""
    }

    serializer match {
      case ser: ByteBufferSerializer ⇒
        headerBuilder.classManifest = manifest
        envelope.writeHeader(headerBuilder)
        ser.toBinary(message, envelope.byteBuffer)
      case _ ⇒
        headerBuilder.classManifest = manifest
        envelope.writeHeader(headerBuilder)
        envelope.byteBuffer.put(serializer.toBinary(message))
    }
  }

  def deserializeForArtery(system: ExtendedActorSystem, serialization: Serialization, headerBuilder: HeaderBuilder, envelope: EnvelopeBuffer): AnyRef = {
    serialization.deserializeByteBuffer(
      envelope.byteBuffer,
      Integer.parseInt(headerBuilder.serializer), // FIXME: Use FQCN
      headerBuilder.classManifest)
  }
}
