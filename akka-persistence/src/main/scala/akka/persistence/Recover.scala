/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.AkkaException

/**
 * Instructs a processor to recover itself. Recovery will start from a snapshot if the processor has
 * previously saved one or more snapshots and at least one of these snapshots matches the specified
 * `fromSnapshot` criteria. Otherwise, recovery will start from scratch by replaying all journaled
 * messages.
 *
 * If recovery starts from a snapshot, the processor is offered that snapshot with a [[SnapshotOffer]]
 * message, followed by replayed messages, if any, that are younger than the snapshot, up to the
 * specified upper sequence number bound (`toSequenceNr`).
 *
 * @param fromSnapshot criteria for selecting a saved snapshot from which recovery should start. Default
 *                     is latest (= youngest) snapshot.
 * @param toSequenceNr upper sequence number bound (inclusive) for recovery. Default is no upper bound.
 */
@SerialVersionUID(1L)
case class Recover(fromSnapshot: SnapshotSelectionCriteria = SnapshotSelectionCriteria.Latest, toSequenceNr: Long = Long.MaxValue)

object Recover {
  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create() = Recover()

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(toSequenceNr: Long) =
    Recover(toSequenceNr = toSequenceNr)

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(fromSnapshot: SnapshotSelectionCriteria) =
    Recover(fromSnapshot = fromSnapshot)

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(fromSnapshot: SnapshotSelectionCriteria, toSequenceNr: Long) =
    Recover(fromSnapshot, toSequenceNr)
}

/**
 * Sent to a [[Processor]] after failed recovery. If not handled, a
 * [[RecoveryFailureException]] is thrown by that processor.
 */
@SerialVersionUID(1L)
case class RecoveryFailure(cause: Throwable)

/**
 * Thrown by a [[Processor]] if a journal failed to replay all requested messages.
 */
@SerialVersionUID(1L)
case class RecoveryFailureException(message: String, cause: Throwable) extends AkkaException(message, cause)

