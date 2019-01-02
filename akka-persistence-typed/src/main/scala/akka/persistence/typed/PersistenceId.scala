/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Unique identifier in the backend data store (journal and snapshot store) of the
 * persistent actor.
 */
final case class PersistenceId(id: String)
