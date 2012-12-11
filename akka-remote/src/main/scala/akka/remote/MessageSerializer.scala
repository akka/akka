/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import language.existentials

import akka.remote.RemoteProtocol._
import com.google.protobuf.ByteString
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializationExtension

/**
 * MessageSerializer is a helper for serializing and deserialize messages
 */
private[akka] object MessageSerializer {

  /**
   * Uses Akka Serialization for the specified ActorSystem to transform the given MessageProtocol to a message
   */
  def deserialize(system: ExtendedActorSystem, messageProtocol: MessageProtocol): AnyRef = {
    SerializationExtension(system).deserialize(
      messageProtocol.getMessage.toByteArray,
      messageProtocol.getSerializerId,
      if (messageProtocol.hasMessageManifest) Some(system.dynamicAccess.getClassFor[AnyRef](messageProtocol.getMessageManifest.toStringUtf8).get) else None).get
  }

  /**
   * Uses Akka Serialization for the specified ActorSystem to transform the given message to a MessageProtocol
   */
  def serialize(system: ExtendedActorSystem, message: AnyRef): MessageProtocol = {
    val s = SerializationExtension(system)
    val serializer = s.findSerializerFor(message)
    val builder = MessageProtocol.newBuilder
    builder.setMessage(ByteString.copyFrom(serializer.toBinary(message)))
    builder.setSerializerId(serializer.identifier)
    if (serializer.includeManifest)
      builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
    builder.build
  }
}
