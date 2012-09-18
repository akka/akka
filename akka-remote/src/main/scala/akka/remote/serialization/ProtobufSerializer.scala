/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.serialization.{ Serializer, Serialization }
import com.google.protobuf.Message
import akka.actor.DynamicAccess
import akka.remote.RemoteProtocol.ActorRefProtocol
import akka.actor.ActorSystem
import akka.actor.ActorRef

object ProtobufSerializer {

  /**
   * Helper to serialize an [[akka.actor.ActorRef]] to Akka's
   * protobuf representation.
   */
  def serializeActorRef(ref: ActorRef): ActorRefProtocol = {
    val identifier: String = Serialization.currentTransportAddress.value match {
      case null    ⇒ ref.path.toString
      case address ⇒ ref.path.toStringWithAddress(address)
    }
    ActorRefProtocol.newBuilder.setPath(identifier).build
  }

  /**
   * Helper to materialize (lookup) an [[akka.actor.ActorRef]]
   * from Akka's protobuf representation in the supplied
   * [[akka.actor.ActorSystem]].
   */
  def deserializeActorRef(system: ActorSystem, refProtocol: ActorRefProtocol): ActorRef =
    system.actorFor(refProtocol.getPath)
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