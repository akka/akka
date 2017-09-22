/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.internal

import java.nio.charset.StandardCharsets

import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import akka.typed.ActorRef
import akka.typed.cluster.ActorRefResolver
import akka.typed.internal.adapter.ActorRefAdapter
import akka.typed.scaladsl.adapter._

@InternalApi
class MiscMessageSerializer(val system: akka.actor.ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  private val resolver = ActorRefResolver(system.toTyped)

  def manifest(o: AnyRef) = o match {
    case ref: ActorRef[_] ⇒ "a"
  }

  def toBinary(o: AnyRef) = o match {
    case ref: ActorRef[_] ⇒ resolver.toSerializationFormat(ref).getBytes(StandardCharsets.UTF_8)
  }

  def fromBinary(bytes: Array[Byte], manifest: String) = manifest match {
    case "a" ⇒ resolver.resolveActorRef(new String(bytes, StandardCharsets.UTF_8))
  }

}