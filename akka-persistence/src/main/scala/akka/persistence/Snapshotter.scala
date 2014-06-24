/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.persistence.SnapshotProtocol._

/**
 * Snapshot API on top of the internal snapshot protocol.
 */
trait Snapshotter extends Actor {
  private lazy val snapshotStore = Persistence(context.system).snapshotStoreFor(snapshotterId)

  /**
   * Snapshotter id.
   */
  def snapshotterId: String

  /**
   * Sequence number to use when taking a snapshot.
   */
  def snapshotSequenceNr: Long

  def loadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long) =
    snapshotStore ! LoadSnapshot(persistenceId, criteria, toSequenceNr)

  /**
   * Saves a `snapshot` of this snapshotter's state. If saving succeeds, this snapshotter will receive a
   * [[SaveSnapshotSuccess]] message, otherwise a [[SaveSnapshotFailure]] message.
   */
  def saveSnapshot(snapshot: Any): Unit = {
    snapshotStore ! SaveSnapshot(SnapshotMetadata(snapshotterId, snapshotSequenceNr), snapshot)
  }

  /**
   * Deletes a snapshot identified by `sequenceNr` and `timestamp`.
   */
  def deleteSnapshot(sequenceNr: Long, timestamp: Long): Unit = {
    snapshotStore ! DeleteSnapshot(SnapshotMetadata(snapshotterId, sequenceNr, timestamp))
  }

  /**
   * Deletes all snapshots matching `criteria`.
   */
  def deleteSnapshots(criteria: SnapshotSelectionCriteria): Unit = {
    snapshotStore ! DeleteSnapshots(snapshotterId, criteria)
  }

}
