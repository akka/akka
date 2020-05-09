/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Thrown if a journal rejects an idempotency key write e.g. if handler is not implemented
 */
class IdempotencyKeyWriteRejectedException(
    persistenceId: PersistenceId,
    idempotencyKey: String,
    sequenceNumber: Long,
    cause: Throwable)
    extends RuntimeException(
      s"Rejected idempotency key write, " +
      s"persistenceId [${persistenceId.id}], " +
      s"idempotencyKey [$idempotencyKey], " +
      s"sequenceNumber [$sequenceNumber]",
      cause)
