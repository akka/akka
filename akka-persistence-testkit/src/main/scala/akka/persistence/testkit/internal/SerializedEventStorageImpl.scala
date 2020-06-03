/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import scala.util.Try

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.annotation.InternalApi
import akka.persistence.PersistentRepr
import akka.persistence.testkit.EventStorage
import akka.serialization.{ Serialization, SerializationExtension }

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SerializedEventStorageImpl(system: ActorSystem) extends EventStorage {

  override type InternalRepr = (Int, Array[Byte])

  private lazy val serialization = SerializationExtension(system)

  /**
   * @return (serializer id, serialized bytes)
   */
  override def toInternal(repr: PersistentRepr): (Int, Array[Byte]) =
    Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
      val s = serialization.findSerializerFor(repr)
      (s.identifier, s.toBinary(repr))
    }

  /**
   * @param internal (serializer id, serialized bytes)
   */
  override def toRepr(internal: (Int, Array[Byte])): PersistentRepr =
    serialization
      .deserialize(internal._2, internal._1, PersistentRepr.Undefined)
      .flatMap(r => Try(r.asInstanceOf[PersistentRepr]))
      .get

}
