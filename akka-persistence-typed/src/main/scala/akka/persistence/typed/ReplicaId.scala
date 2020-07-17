/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Identifies a replica in Active Active event sourcing, could be a data center name or a logical identifier.
 */
final case class ReplicaId(id: String) extends AnyVal
