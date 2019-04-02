/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.annotation.InternalApi
import akka.persistence.typed.scaladsl
import akka.persistence.typed.javadsl

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class SnapshotCountRetentionCriteriaImpl(
    snapshotEveryNEvents: Int,
    keepNSnapshots: Int,
    deleteEventsOnSnapshot: Boolean)
    extends javadsl.SnapshotCountRetentionCriteria
    with scaladsl.SnapshotCountRetentionCriteria {

  require(snapshotEveryNEvents > 0, s"'snapshotEveryNEvents' must be greater than 0, was [$snapshotEveryNEvents]")
  require(keepNSnapshots > 0, s"'keepNSnapshots' must be greater than 0, was [$keepNSnapshots]")

  def snapshotWhen(currentSequenceNr: Long): Boolean =
    currentSequenceNr % snapshotEveryNEvents == 0

  def deleteUpperSequenceNr(lastSequenceNr: Long): Long = {
    // Delete old events, retain the latest
    math.max(0, lastSequenceNr - (keepNSnapshots * snapshotEveryNEvents))
  }

  def deleteLowerSequenceNr(upperSequenceNr: Long): Long = {
    // We could use 0 as fromSequenceNr to delete all older snapshots, but that might be inefficient for
    // large ranges depending on how it's implemented in the snapshot plugin. Therefore we use the
    // same window as defined for how much to keep in the retention criteria
    math.max(0, upperSequenceNr - (keepNSnapshots * snapshotEveryNEvents))
  }

  override def withDeleteEventsOnSnapshot: SnapshotCountRetentionCriteriaImpl =
    copy(deleteEventsOnSnapshot = true)

  override def asScala: scaladsl.RetentionCriteria = this

  override def asJava: javadsl.RetentionCriteria = this
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] case object DisabledRetentionCriteria
    extends javadsl.RetentionCriteria
    with scaladsl.RetentionCriteria {
  override def asScala: scaladsl.RetentionCriteria = this
  override def asJava: javadsl.RetentionCriteria = this
}
