/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

/**
 * @param persistenceId The persistence id of the origin entity.
 * @param seqNr The sequence number from the origin entity.
 * @param value The object value.
 * @param offset The offset that can be used in next `changes` or `currentChanges` query.
 * @param timestamp The time the state was stored, in milliseconds since midnight, January 1, 1970 UTC
 *   (same as `System.currentTimeMillis`).
 *
 * @tparam A the type of the value
 */
final class DurableStateChange[A](
    val persistenceId: String,
    val revision: Long,
    val value: A,
    val offset: Offset,
    val timestamp: Long)
