/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

// FIXME not a case class, validation
object ReplicationId {
  def fromString(id: String): ReplicationId = {
    val split = id.split("\\|")
    ReplicationId(split(0), split(1), ReplicaId(split(2)))
  }
}

/**
 *
 * @param typeName The name of the entity type e.g. account, user. Made part of the persistence id so that entity ids don't need to be unique across different replicated entities
 * @param entityId The unique entity id
 * @param replicaId The unique identity for this entity. The underlying persistence id will include the replica.
 *
 */
case class ReplicationId(typeName: String, entityId: String, replicaId: ReplicaId) {
  def id: String = s"$typeName|$entityId|${replicaId.id}"
  def persistenceId: PersistenceId = PersistenceId.ofUniqueId(id)
}
