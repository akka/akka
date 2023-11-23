/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Identifies a replica in Replicated Event Sourcing, could be a datacenter name or a logical identifier.
 */
final case class ReplicaId(id: String) {
  override def toString: String = id
}
