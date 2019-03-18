/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.typed.Signal
import akka.annotation.DoNotInherit
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria

/**
 * Supertype for all Akka Persistence Typed specific signals
 *
 * Not for user extension
 */
@DoNotInherit
sealed trait EventSourcedSignal extends Signal

final case class RecoveryCompleted[State](state: State) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getState(): State = state
}
final case class RecoveryFailed(failure: Throwable) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure
}

final case class SnapshotCompleted(metadata: SnapshotMetadata) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getSnapshotMetadata(): SnapshotMetadata = metadata
}
final case class SnapshotFailed(metadata: SnapshotMetadata, failure: Throwable) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure

  /**
   * Java API
   */
  def getSnapshotMetadata(): SnapshotMetadata = metadata
}

final case class DeleteSnapshotsCompleted(target: DeletionTarget) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getTarget(): DeletionTarget = target
}
final case class DeleteSnapshotsFailed(target: DeletionTarget, failure: Throwable) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure

  /**
   * Java API
   */
  def getTarget(): DeletionTarget = target
}

final case class DeleteMessagesCompleted(toSequenceNr: Long) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getToSequenceNr(): Long = toSequenceNr
}
final case class DeleteMessagesFailed(toSequenceNr: Long, failure: Throwable) extends EventSourcedSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure

  /**
   * Java API
   */
  def getToSequenceNr(): Long = toSequenceNr
}

/**
 * Not for user extension
 */
@DoNotInherit
sealed trait DeletionTarget
object DeletionTarget {
  final case class Individual(metadata: SnapshotMetadata) extends DeletionTarget {

    /**
     * Java API
     */
    def getSnapshotMetadata(): SnapshotMetadata = metadata
  }
  final case class Criteria(selection: SnapshotSelectionCriteria) extends DeletionTarget {

    /**
     * Java API
     */
    def getSnapshotSelection(): SnapshotSelectionCriteria = selection
  }
}
