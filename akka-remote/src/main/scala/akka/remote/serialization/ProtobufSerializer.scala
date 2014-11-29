/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ActorRef, ExtendedActorSystem}
import akka.remote.WireFormats.ActorRefData
import akka.serialization.{Serialization, Serializer}
import com.google.protobuf.{Message, Parser}

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
  private val serializerBinding = new ConcurrentHashMap[Class[_],Parser[Message]]()

  override def identifier: Int = 2

  override def includeManifest: Boolean = true

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    manifest match {
      case Some(clazz)=>
        val cachedParser = serializerBinding.get(clazz)
        if (cachedParser ne null){
          cachedParser.parseFrom(bytes)
        }else{
          val parser = clazz.getField("PARSER").get(null).asInstanceOf[Parser[Message]]
          val previousParser = serializerBinding.putIfAbsent(clazz,parser)
          if (previousParser ne null){
            previousParser.parseFrom(bytes)
          }else {
            parser.parseFrom(bytes)
          }
        }
      case None => throw new IllegalArgumentException("Need a protobuf message class to be able to serialize bytes using protobuf")
    }
  }

  override def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case message:Message => message.toByteArray
    case _=>throw new IllegalArgumentException(s"Can't serialize a non-protobuf message using protobuf [$obj]")
  }
}
