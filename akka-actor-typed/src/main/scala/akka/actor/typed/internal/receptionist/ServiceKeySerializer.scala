/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.internal.receptionist

import java.io.NotSerializableException
import java.nio.charset.StandardCharsets

import akka.actor.typed.receptionist.ServiceKey
import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

@InternalApi
class ServiceKeySerializer(val system: akka.actor.ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  private val ServiceKeyManifest = "a"

  def manifest(o: AnyRef): String = o match {
    case _: ServiceKey[_] ⇒ ServiceKeyManifest
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case serviceKey: ServiceKey[_] ⇒ serviceKey.id.getBytes(StandardCharsets.UTF_8)
    case _ ⇒
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  def fromBinary(bytes: Array[Byte], manifest: String): ServiceKey[Any] = manifest match {
    case ServiceKeyManifest ⇒ ServiceKey[Any](new String(bytes, StandardCharsets.UTF_8))
    case _ ⇒
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }
}
