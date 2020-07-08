/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Identifies a replica in Active Active eventsourcing, could be a datacenter name or a logical identifier.
 */
final case class ReplicaId(id: String)
