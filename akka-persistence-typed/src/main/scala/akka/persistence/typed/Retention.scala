/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * Setup snapshot and event delete/retention behavior. Retention bridges snapshot
 * and journal behavior. This defines the retention criteria.
 *
 * @param snapshotEveryNEvents Snapshots are used to reduce playback/recovery times.
 *                             This defines when a new snapshot is persisted - on every N events.
 *                            `snapshotEveryNEvents` should be greater than 0.
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
    math.max(0, lastSequenceNr - (keepNSnapshots * snapshotEveryNEvents))
  }

  /** Java API. */
  def withDeleteEventsOnSnapshot(): RetentionCriteria =
    copy(deleteEventsOnSnapshot = true)
}

object RetentionCriteria {

  val disabled: RetentionCriteria =
    RetentionCriteria(snapshotEveryNEvents = 0, keepNSnapshots = 0, deleteEventsOnSnapshot = false)

  def snapshotEvery(numberOfEvents: Long, keepNSnapshots: Long): RetentionCriteria =
    apply(numberOfEvents, keepNSnapshots, false)

}
