/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Thrown by PersistentBehaviors if a persist calls failed.
 * Can be used in supervision strategies to handle journal outages.
 */
case class PersistFailedException(persistenceId: String, sequenceNr: Long, eventType: String, cause: Throwable)
  extends RuntimeException(s"Failed to persist event type $eventType with sequence number $sequenceNr for persistenceId [$persistenceId]", cause) {
}
