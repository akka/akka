/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.typed

import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import akka.serialization.SerializerWithStringManifest
import docs.akka.cluster.typed.PingPongExample._

//#serializer
class PingSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  private val actorRefResolver = ActorRefResolver(system.toTyped)

  private val PingManifest = "a"
  private val PongManifest = "b"

  override def identifier = 41

  override def manifest(msg: AnyRef) = msg match {
    case _: Ping ⇒ PingManifest
    case Pong    ⇒ PongManifest
  }

  override def toBinary(msg: AnyRef) = msg match {
    case Ping(who) ⇒
      actorRefResolver.toSerializationFormat(who).getBytes(StandardCharsets.UTF_8)
    case Pong ⇒
      Array.emptyByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String) = {
    manifest match {
      case PingManifest ⇒
        val str = new String(bytes, StandardCharsets.UTF_8)
        val ref = actorRefResolver.resolveActorRef[Pong.type](str)
        Ping(ref)
      case PongManifest ⇒
        Pong
    }
  }
}
//#serializer
