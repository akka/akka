/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import akka.remote.WireFormats._
import akka.protobuf.ByteString
import akka.actor.ExtendedActorSystem
import akka.remote.artery.{ EnvelopeBuffer, HeaderBuilder, OutboundEnvelope }
import akka.serialization.Serialization
import akka.serialization.ByteBufferSerializer
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import scala.util.control.NonFatal

/**
 * INTERNAL API
 *
 * MessageSerializer is a helper for serializing and deserialize messages
 */
private[akka] object MessageSerializer {

  class SerializationException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

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
   * Throws `NotSerializableException` if serializer was not configured for the message type.
   * Throws `MessageSerializer.SerializationException` if exception was thrown from `toBinary` of the
   * serializer.
   */
  def serialize(system: ExtendedActorSystem, message: AnyRef): SerializedMessage = {
    val s = SerializationExtension(system)
    val serializer = s.findSerializerFor(message)
    val builder = SerializedMessage.newBuilder
    try {
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
    } catch {
      case NonFatal(e) ⇒
        throw new SerializationException(s"Failed to serialize remote message [${message.getClass}] " +
          s"using serializer [${serializer.getClass}].", e)
    }
  }

  def serializeForArtery(serialization: Serialization, outboundEnvelope: OutboundEnvelope, headerBuilder: HeaderBuilder, envelope: EnvelopeBuffer): Unit = {
    val message = outboundEnvelope.message
    val serializer = serialization.findSerializerFor(message)

    headerBuilder setSerializer serializer.identifier

    def manifest: String = serializer match {
      case ser: SerializerWithStringManifest ⇒ ser.manifest(message)
      case _                                 ⇒ if (serializer.includeManifest) message.getClass.getName else ""
    }

    serializer match {
      case ser: ByteBufferSerializer ⇒
        headerBuilder setManifest manifest
        envelope.writeHeader(headerBuilder, outboundEnvelope)
        ser.toBinary(message, envelope.byteBuffer)
      case _ ⇒
        headerBuilder setManifest manifest
        envelope.writeHeader(headerBuilder, outboundEnvelope)
        envelope.byteBuffer.put(serializer.toBinary(message))
    }
  }

  def deserializeForArtery(system: ExtendedActorSystem, originUid: Long, serialization: Serialization,
                           serializer: Int, classManifest: String, envelope: EnvelopeBuffer): AnyRef = {
    serialization.deserializeByteBuffer(envelope.byteBuffer, serializer, classManifest)
  }
}
