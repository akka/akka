/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

import akka.annotation.DoNotInherit

/**
 * The `DurableStateStoreQuery` stream elements for `DurableStateStoreQuery`.
 *
 * The implementation can be a [[UpdatedDurableState]] or a `DeletedDurableState`.
 * `DeletedDurableState` is not implemented yet, see issue https://github.com/akka/akka/issues/30446
 *
 * Not for user extension
 *
 * @tparam A the type of the value
 */
@DoNotInherit
sealed trait DurableStateChange[A] {

  /**
   * The persistence id of the origin entity.
   */
  def persistenceId: String

  /**
   * The offset that can be used in next `changes` or `currentChanges` query.
   */
  def offset: Offset
}

object UpdatedDurableState {

  def unapply[A](arg: UpdatedDurableState[A]): Option[(String, Long, A, Offset, Long)] =
    Some((arg.persistenceId, arg.revision, arg.value, arg.offset, arg.timestamp))
}

/**
 *
 * @param persistenceId The persistence id of the origin entity.
 * @param revision The revision number from the origin entity.
 * @param value The object value.
 * @param offset The offset that can be used in next `changes` or `currentChanges` query.
 * @param timestamp The time the state was stored, in milliseconds since midnight, January 1, 1970 UTC
 *   (same as `System.currentTimeMillis`).
 * @tparam A the type of the value
 */
final class UpdatedDurableState[A](
    val persistenceId: String,
    val revision: Long,
    val value: A,
    override val offset: Offset,
    val timestamp: Long)
    extends DurableStateChange[A]
