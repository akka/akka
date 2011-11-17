/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.remote.RemoteProtocol._
import akka.serialization.Serialization
import com.google.protobuf.ByteString
import akka.actor.ActorSystem

object MessageSerializer {

  def deserialize(system: ActorSystem, messageProtocol: MessageProtocol, classLoader: Option[ClassLoader] = None): AnyRef = {
    val clazz = loadManifest(classLoader, messageProtocol)
    system.serialization.deserialize(messageProtocol.getMessage.toByteArray,
      clazz, classLoader).fold(x ⇒ throw x, identity)
  }

  def serialize(system: ActorSystem, message: AnyRef): MessageProtocol = {
    val builder = MessageProtocol.newBuilder
    val bytes = system.serialization.serialize(message).fold(x ⇒ throw x, identity)
    builder.setMessage(ByteString.copyFrom(bytes))
    builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
    builder.build
  }

  private def loadManifest(classLoader: Option[ClassLoader], messageProtocol: MessageProtocol): Class[_] = {
    val manifest = messageProtocol.getMessageManifest.toStringUtf8
    classLoader map (_.loadClass(manifest)) getOrElse (Class.forName(manifest))
  }
}
