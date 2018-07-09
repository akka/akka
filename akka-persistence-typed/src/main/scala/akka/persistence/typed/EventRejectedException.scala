/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Thrown if a journal rejects an event e.g. due to a serialization error.
 */
final class EventRejectedException(persistenceId: String, sequenceNr: Long, cause: Throwable)
  extends RuntimeException(s"PersistenceId $persistenceId sequenceNr: $sequenceNr", cause)
