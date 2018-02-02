/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.internal.receptionist

import java.nio.charset.StandardCharsets

import akka.actor.typed.internal.receptionist.ReceptionistImpl.DefaultServiceKey
import akka.actor.typed.receptionist.ServiceKey
import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

@InternalApi
class ServiceKeySerializer(val system: akka.actor.ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {
  def manifest(o: AnyRef): String = o match {
    case key: DefaultServiceKey[_] ⇒ key.typeName
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case serviceKey: DefaultServiceKey[_] ⇒ serviceKey.id.getBytes(StandardCharsets.UTF_8)
    case _ ⇒
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  def fromBinary(bytes: Array[Byte], manifest: String): ServiceKey[Any] =
    DefaultServiceKey[Any](new String(bytes, StandardCharsets.UTF_8), manifest)
}
