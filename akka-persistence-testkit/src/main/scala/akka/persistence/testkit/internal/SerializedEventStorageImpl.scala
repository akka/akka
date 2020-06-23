/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import scala.util.Try
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.annotation.InternalApi
import akka.persistence.PersistentRepr
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.EventStorage.Metadata
import akka.serialization.{ Serialization, SerializationExtension }

/**
 * INTERNAL API
 * FIXME, once we add serializers for metadata serialize the metadata payload if present
 */
@InternalApi
private[testkit] class SerializedEventStorageImpl(system: ActorSystem) extends EventStorage {
  override type InternalRepr = (Int, Array[Byte], Metadata)

  private lazy val serialization = SerializationExtension(system)

  /**
   * @return (serializer id, serialized bytes)
   */
  override def toInternal(repr: (PersistentRepr, Metadata)): (Int, Array[Byte], Metadata) =
    Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
      val s = serialization.findSerializerFor(repr)
      (s.identifier, s.toBinary(repr._1), repr._2)
    }

  /**
   * FIXME, this serialized the persistent repr, it should serialize the payload instead
   * @param internal (serializer id, serialized bytes)
   *
   */
  override def toRepr(internal: (Int, Array[Byte], Metadata)): (PersistentRepr, Metadata) =
    (
      serialization
        .deserialize(internal._2, internal._1, PersistentRepr.Undefined)
        .flatMap(r => Try(r.asInstanceOf[PersistentRepr]))
        .get,
      internal._3)

}
