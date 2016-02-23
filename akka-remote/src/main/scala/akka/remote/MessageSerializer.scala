/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import akka.remote.WireFormats._
import akka.protobuf.ByteString
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest

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
}
