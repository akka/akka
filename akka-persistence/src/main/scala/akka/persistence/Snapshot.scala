/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence

/**
 * Snapshot metadata.
 *
 * @param processorId id of processor from which the snapshot was taken.
 * @param sequenceNr sequence number at which the snapshot was taken.
 * @param timestamp time at which the snapshot was saved.
 */
@SerialVersionUID(1L) //#snapshot-metadata
case class SnapshotMetadata(processorId: String, sequenceNr: Long, timestamp: Long = 0L)
//#snapshot-metadata

/**
 * Notification of a snapshot saving success.
 *
 * @param metadata snapshot metadata.
 */
@SerialVersionUID(1L)
case class SaveSnapshotSuccess(metadata: SnapshotMetadata)

/**
 * Notification of a snapshot saving success failure.
 *
 * @param metadata snapshot metadata.
 * @param cause failure cause.
 */
@SerialVersionUID(1L)
case class SaveSnapshotFailure(metadata: SnapshotMetadata, cause: Throwable)

/**
 * Offers a [[Processor]] a previously saved `snapshot` during recovery. This offer is received
 * before any further replayed messages.
 */
@SerialVersionUID(1L)
case class SnapshotOffer(metadata: SnapshotMetadata, snapshot: Any)

/**
 * Selection criteria for loading snapshots.
 *
 * @param maxSequenceNr upper bound for a selected snapshot's sequence number. Default is no upper bound.
 * @param maxTimestamp upper bound for a selected snapshot's timestamp. Default is no upper bound.
 *
 * @see [[Recover]]
 */
@SerialVersionUID(1L)
case class SnapshotSelectionCriteria(maxSequenceNr: Long = Long.MaxValue, maxTimestamp: Long = Long.MaxValue) {
  private[persistence] def limit(toSequenceNr: Long): SnapshotSelectionCriteria =
    if (toSequenceNr < maxSequenceNr) copy(maxSequenceNr = toSequenceNr) else this
}

object SnapshotSelectionCriteria {
  /**
   * The latest saved snapshot.
   */
  val Latest = SnapshotSelectionCriteria()

  /**
   * No saved snapshot matches.
   */
  val None = SnapshotSelectionCriteria(0L, 0L)

  /**
   * Java API.
   */
  def create(maxSequenceNr: Long, maxTimestamp: Long) =
    SnapshotSelectionCriteria(maxSequenceNr, maxTimestamp)

  /**
   * Java API.
   */
  def latest() = Latest

  /**
   * Java API.
   */
  def none() = None
}

/**
 * Plugin API.
 *
 * A selected snapshot matching [[SnapshotSelectionCriteria]].
 *
 * @param metadata snapshot metadata.
 * @param snapshot snapshot.
 */
case class SelectedSnapshot(metadata: SnapshotMetadata, snapshot: Any)

object SelectedSnapshot {
  /**
   * Plugin Java API.
   */
  def create(metadata: SnapshotMetadata, snapshot: Any): SelectedSnapshot =
    SelectedSnapshot(metadata, snapshot)
}

/**
 * Defines messages exchanged between processors and a snapshot store.
 */
private[persistence] object SnapshotProtocol {
  /**
   * Instructs a snapshot store to load a snapshot.
   *
   * @param processorId processor id.
   * @param criteria criteria for selecting a snapshot from which recovery should start.
   * @param toSequenceNr upper sequence number bound (inclusive) for recovery.
   */
  case class LoadSnapshot(processorId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long)

  /**
   * Response message to a [[LoadSnapshot]] message.
   *
   * @param snapshot loaded snapshot, if any.
   */
  case class LoadSnapshotResult(snapshot: Option[SelectedSnapshot], toSequenceNr: Long)

  /**
   * Instructs snapshot store to save a snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  case class SaveSnapshot(metadata: SnapshotMetadata, snapshot: Any)
}
