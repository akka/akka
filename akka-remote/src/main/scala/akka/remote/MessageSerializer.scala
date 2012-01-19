/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.remote.RemoteProtocol._
import com.google.protobuf.ByteString
import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.util.ReflectiveAccess

object MessageSerializer {

  def deserialize(system: ActorSystem, messageProtocol: MessageProtocol, classLoader: Option[ClassLoader] = None): AnyRef = {
    val clazz = if (messageProtocol.hasMessageManifest) {
      Option(ReflectiveAccess.getClassFor[AnyRef](
        messageProtocol.getMessageManifest.toStringUtf8,
        classLoader.getOrElse(ReflectiveAccess.loader)) match {
          case Left(e)  ⇒ throw e
          case Right(r) ⇒ r
        })
    } else None
    SerializationExtension(system).deserialize(
      messageProtocol.getMessage.toByteArray,
      messageProtocol.getSerializerId,
      clazz,
      classLoader) match {
        case Left(e)  ⇒ throw e
        case Right(r) ⇒ r
      }
  }

  def serialize(system: ActorSystem, message: AnyRef): MessageProtocol = {
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
