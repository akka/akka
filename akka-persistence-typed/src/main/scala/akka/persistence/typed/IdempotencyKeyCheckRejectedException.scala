/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Thrown if a journal rejects an idempotency key check e.g. if handler is not implemented
 */
class IdempotencyKeyCheckRejectedException(persistenceId: PersistenceId, idempotencyKey: String, cause: Throwable)
    extends RuntimeException(
      s"Rejected idempotency key check, persistenceId [${persistenceId.id}], idempotencyKey [$idempotencyKey]",
      cause)
