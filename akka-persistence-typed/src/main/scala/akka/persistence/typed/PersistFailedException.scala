/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * Used for journal failures. Private to akka as only internal supervision strategies should use it.
 */
@InternalApi
private[akka] case class PersistFailedException(persistenceId: String, sequenceNr: Long, eventType: String, cause: Throwable)
  extends RuntimeException(s"Failed to persist event type $eventType with sequence number $sequenceNr for persistenceId [$persistenceId]", cause) {
}
