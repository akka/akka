/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Unique identifier in the backend data store (journal and snapshot store) of the
 * persistent actor.
 */
final case class PersistenceId(id: String) {
  if (id eq null)
    throw new IllegalArgumentException("persistenceId must not be null")

  if (id.trim.isEmpty)
    throw new IllegalArgumentException("persistenceId must not be empty")
}
