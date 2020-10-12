/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.annotation.InternalApi
import akka.persistence.PersistentRepr
import akka.persistence.journal.Tagged
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.internal.SerializedEventStorageImpl.Serialized
import akka.serialization.{ Serialization, SerializationExtension, Serializers }

@InternalApi
private[testkit] object SerializedEventStorageImpl {
  case class Serialized(
      persistenceId: String,
      sequenceNr: Long,
      payloadSerId: Int,
      payloadSerManifest: String,
      writerUuid: String,
      payload: Array[Byte],
      tags: Set[String],
      metadata: Option[Any])
}

/**
 * INTERNAL API
 * FIXME, once we add serializers for metadata serialize the metadata payload if present
 */
@InternalApi
private[testkit] class SerializedEventStorageImpl(system: ActorSystem) extends EventStorage {
  override type InternalRepr = Serialized

  private lazy val serialization = SerializationExtension(system)

  /**
   * @return (serializer id, serialized bytes)
   */
  override def toInternal(pr: PersistentRepr): Serialized =
    Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
      val (payload, tags) = pr.payload match {
        case Tagged(event: AnyRef, tags) => (event, tags)
        case event: AnyRef               => (event, Set.empty[String])
      }
      val s = serialization.findSerializerFor(payload)
      val manifest = Serializers.manifestFor(s, payload)
      Serialized(
        pr.persistenceId,
        pr.sequenceNr,
        s.identifier,
        manifest,
        pr.writerUuid,
        s.toBinary(payload),
        tags,
        pr.metadata)
    }

  /**
   * @param internal (serializer id, serialized bytes)
   */
  override def toRepr(internal: Serialized): PersistentRepr = {
    val event = serialization.deserialize(internal.payload, internal.payloadSerId, internal.payloadSerManifest).get
    val eventForRepr =
      if (internal.tags.isEmpty) event
      else Tagged(event, internal.tags)
    val pr = PersistentRepr(eventForRepr, internal.sequenceNr, internal.persistenceId, writerUuid = internal.writerUuid)
    internal.metadata.fold(pr)(meta => pr.withMetadata(meta))
  }

}
