/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.typed.internal

import java.nio.charset.StandardCharsets

import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import akka.actor.typed.ActorRef
import akka.cluster.typed.ActorRefResolver
import akka.actor.typed.scaladsl.adapter._
import java.io.NotSerializableException

@InternalApi
class MiscMessageSerializer(val system: akka.actor.ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  private val resolver = ActorRefResolver(system.toTyped)

  def manifest(o: AnyRef) = o match {
    case ref: ActorRef[_] ⇒ "a"
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  def toBinary(o: AnyRef) = o match {
    case ref: ActorRef[_] ⇒ resolver.toSerializationFormat(ref).getBytes(StandardCharsets.UTF_8)
    case _ ⇒
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  def fromBinary(bytes: Array[Byte], manifest: String) = manifest match {
    case "a" ⇒ resolver.resolveActorRef(new String(bytes, StandardCharsets.UTF_8))
    case _ ⇒
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

}
