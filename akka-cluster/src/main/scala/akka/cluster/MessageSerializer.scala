/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.cluster.RemoteProtocol._
import akka.serialization.Serialization

import com.google.protobuf.ByteString

object MessageSerializer {

  def deserialize(messageProtocol: MessageProtocol, classLoader: Option[ClassLoader] = None): AnyRef = {
    val clazz = loadManifest(classLoader, messageProtocol)
    Serialization.deserialize(messageProtocol.getMessage.toByteArray,
      clazz, classLoader).fold(x ⇒ throw x, o ⇒ o)
  }

  def serialize(message: AnyRef): MessageProtocol = {
    val builder = MessageProtocol.newBuilder
    val bytes = Serialization.serialize(message).fold(x ⇒ throw x, b ⇒ b)
    builder.setMessage(ByteString.copyFrom(bytes))
    builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
    builder.build
  }

  private def loadManifest(classLoader: Option[ClassLoader], messageProtocol: MessageProtocol): Class[_] = {
    val manifest = messageProtocol.getMessageManifest.toStringUtf8
    classLoader map (_.loadClass(manifest)) getOrElse (Class.forName(manifest))
  }
}
