/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.annotation.InternalApi

/**
 * Facility to convert snapshots from and to a specialized data model.
 * Can be used when migration from different state types e.g. when migration
 * from Persistent FSM to Typed Persistence.
 *
 * @tparam State The state type of the `EventSourcedBehavior`
 */
trait SnapshotAdapter[State] {

  /**
   * Transform the state to a different type before sending to the journal.
   */
  def toJournal(state: State): Any

  /**
   * Transform the stored state into the current state type.
   * Can be used for migrations from different serialized state types.
   */
  def fromJournal(from: Any): State
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class NoOpSnapshotAdapter extends SnapshotAdapter[Any] {
  override def toJournal(state: Any): Any = state
  override def fromJournal(from: Any): Any = from
}

/**
 * INTERNAL API
 */
@InternalApi
object NoOpSnapshotAdapter {
  val i = new NoOpSnapshotAdapter
  def instance[S]: SnapshotAdapter[S] = i.asInstanceOf[SnapshotAdapter[S]]
}
