/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Setup snapshot and event delete/retention behavior. Retention bridges snapshot
 * and journal behavior. This defines the retention criteria.
 *
 * @param snapshotEveryNEvents Snapshots are used to reduce playback/recovery times.
 *                             This defines when a new snapshot is persisted.
 *
 * @param keepNSnapshots      After a snapshot is successfully completed,
 *                             - if 2: retain last maximum 2 *`snapshot-size` events
 *                             and 3 snapshots (2 old + latest snapshot)
 *                             - if 0: all events with equal or lower sequence number
 *                             will not be retained.
 *
 * @param deleteEventsOnSnapshot Opt-in ability to delete older events on successful
 *                               save of snapshot. Defaults to disabled.
 */
final case class RetentionCriteria(snapshotEveryNEvents: Long, keepNSnapshots: Long, deleteEventsOnSnapshot: Boolean) {

  /**
   * Delete Messages:
   *   {{{ toSequenceNr - keepNSnapshots * snapshotEveryNEvents }}}
   * Delete Snapshots:
   *   {{{ (toSequenceNr - 1) - (keepNSnapshots * snapshotEveryNEvents) }}}
   *
   * @param lastSequenceNr the sequence number to delete to if `deleteEventsOnSnapshot` is false
   */
  def toSequenceNumber(lastSequenceNr: Long): Long = {
    // Delete old events, retain the latest
    lastSequenceNr - (keepNSnapshots * snapshotEveryNEvents)
  }
}

object RetentionCriteria {

  def apply(): RetentionCriteria =
    RetentionCriteria(snapshotEveryNEvents = 1000L, keepNSnapshots = 2L, deleteEventsOnSnapshot = false)

  /** Scala API. */
  def apply(snapshotEveryNEvents: Long, keepNSnapshots: Long): RetentionCriteria =
    RetentionCriteria(snapshotEveryNEvents, keepNSnapshots, deleteEventsOnSnapshot = false)

  /** Java API. */
  def create(snapshotEveryNEvents: Long, keepNSnapshots: Long): RetentionCriteria =
    apply(snapshotEveryNEvents, keepNSnapshots)

  /** Java API. */
  def create(snapshotEveryNEvents: Long, keepNSnapshots: Long, deleteMessagesOnSnapshot: Boolean): RetentionCriteria =
    apply(snapshotEveryNEvents, keepNSnapshots, deleteMessagesOnSnapshot)
}
