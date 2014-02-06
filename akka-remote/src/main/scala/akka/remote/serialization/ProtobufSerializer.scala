/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.actor.{ ExtendedActorSystem, ActorRef }
import akka.remote.WireFormats.ActorRefData
import akka.serialization.{ Serializer, Serialization }
import com.google.protobuf.Message

object ProtobufSerializer {

  /**
   * Helper to serialize an [[akka.actor.ActorRef]] to Akka's
   * protobuf representation.
   */
  def serializeActorRef(ref: ActorRef): ActorRefData = {
    ActorRefData.newBuilder.setPath(Serialization.serializedActorPath(ref)).build
  }

  /**
   * Helper to materialize (lookup) an [[akka.actor.ActorRef]]
   * from Akka's protobuf representation in the supplied
   * [[akka.actor.ActorSystem]].
   */
  def deserializeActorRef(system: ExtendedActorSystem, refProtocol: ActorRefData): ActorRef =
    system.provider.resolveActorRef(refProtocol.getPath)
}

/**
 * This Serializer serializes `com.google.protobuf.Message`s
 */
class ProtobufSerializer extends Serializer {
  val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])
  def includeManifest: Boolean = true
  def identifier = 2

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: Message ⇒ m.toByteArray
    case _          ⇒ throw new IllegalArgumentException("Can't serialize a non-protobuf message using protobuf [" + obj + "]")
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef =
    clazz match {
      case None    ⇒ throw new IllegalArgumentException("Need a protobuf message class to be able to serialize bytes using protobuf")
      case Some(c) ⇒ c.getDeclaredMethod("parseFrom", ARRAY_OF_BYTE_ARRAY: _*).invoke(null, bytes).asInstanceOf[Message]
    }
}
