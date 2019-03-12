/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal

import java.io.NotSerializableException

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorRefResolver
import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.typed.internal.protobuf.ClusterMessages
import akka.cluster.typed.internal.receptionist.ClusterReceptionist.Entry

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class AkkaClusterTypedSerializer(override val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  // Serializers are initialized early on. `toTyped` might then try to initialize the untyped ActorSystemAdapter extension.
  private lazy val resolver = ActorRefResolver(system.toTyped)
  private val ReceptionistEntryManifest = "a"

  override def manifest(o: AnyRef): String = o match {
    case _: Entry => ReceptionistEntryManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: Entry => receptionistEntryToBinary(e)
    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case ReceptionistEntryManifest => receptionistEntryFromBinary(bytes)
    case _ =>
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

  private def receptionistEntryToBinary(e: Entry): Array[Byte] =
    ClusterMessages.ReceptionistEntry
      .newBuilder()
      .setActorRef(resolver.toSerializationFormat(e.ref))
      .setSystemUid(e.systemUid)
      .build()
      .toByteArray

  private def receptionistEntryFromBinary(bytes: Array[Byte]): Entry = {
    val re = ClusterMessages.ReceptionistEntry.parseFrom(bytes)
    Entry(resolver.resolveActorRef(re.getActorRef), re.getSystemUid)
  }
}
