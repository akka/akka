/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 * Copyright (C) 2012-2016 Eligotech BV.
 */

package akka.persistence

/**
 * Snapshot metadata.
 *
 * @param persistenceId id of persistent actor from which the snapshot was taken.
 * @param sequenceNr sequence number at which the snapshot was taken.
 * @param timestamp time at which the snapshot was saved, defaults to 0 when unknown.
 */
@SerialVersionUID(1L) //#snapshot-metadata
final case class SnapshotMetadata(persistenceId: String, sequenceNr: Long, timestamp: Long = 0L)
//#snapshot-metadata

object SnapshotMetadata {
  implicit val ordering: Ordering[SnapshotMetadata] = Ordering.fromLessThan[SnapshotMetadata] { (a, b) ⇒
    if (a eq b) false
    else if (a.persistenceId != b.persistenceId) a.persistenceId.compareTo(b.persistenceId) < 0
    else if (a.sequenceNr != b.sequenceNr) a.sequenceNr < b.sequenceNr
    else if (a.timestamp != b.timestamp) a.timestamp < b.timestamp
    else false
  }
}

/**
 * Sent to a [[PersistentActor]] after successful saving of a snapshot.
 *
 * @param metadata snapshot metadata.
 */
@SerialVersionUID(1L)
final case class SaveSnapshotSuccess(metadata: SnapshotMetadata)
  extends SnapshotProtocol.Response

/**
 * Sent to a [[PersistentActor]] after successful deletion of a snapshot.
 *
 * @param metadata snapshot metadata.
 */
@SerialVersionUID(1L)
final case class DeleteSnapshotSuccess(metadata: SnapshotMetadata)
  extends SnapshotProtocol.Response

/**
 * Sent to a [[PersistentActor]] after successful deletion of specified range of snapshots.
 *
 * @param criteria snapshot selection criteria.
 */
@SerialVersionUID(1L)
final case class DeleteSnapshotsSuccess(criteria: SnapshotSelectionCriteria)
  extends SnapshotProtocol.Response

/**
 * Sent to a [[PersistentActor]] after failed saving of a snapshot.
 *
 * @param metadata snapshot metadata.
 * @param cause failure cause.
 */
@SerialVersionUID(1L)
final case class SaveSnapshotFailure(metadata: SnapshotMetadata, cause: Throwable)
  extends SnapshotProtocol.Response

/**
 * Sent to a [[PersistentActor]] after failed deletion of a snapshot.
 *
 * @param metadata snapshot metadata.
 * @param cause failure cause.
 */
@SerialVersionUID(1L)
final case class DeleteSnapshotFailure(metadata: SnapshotMetadata, cause: Throwable)
  extends SnapshotProtocol.Response

/**
 * Sent to a [[PersistentActor]] after failed deletion of a range of snapshots.
 *
 * @param criteria snapshot selection criteria.
 * @param cause failure cause.
 */
@SerialVersionUID(1L)
final case class DeleteSnapshotsFailure(criteria: SnapshotSelectionCriteria, cause: Throwable)
  extends SnapshotProtocol.Response

/**
 * Offers a [[PersistentActor]] a previously saved `snapshot` during recovery. This offer is received
 * before any further replayed messages.
 */
@SerialVersionUID(1L)
final case class SnapshotOffer(metadata: SnapshotMetadata, snapshot: Any)

/**
 * Selection criteria for loading and deleting snapshots.
 *
 * @param maxSequenceNr upper bound for a selected snapshot's sequence number. Default is no upper bound,
 *   i.e. `Long.MaxValue`
 * @param maxTimestamp upper bound for a selected snapshot's timestamp. Default is no upper bound,
 *   i.e. `Long.MaxValue`
 * @param minSequenceNr lower bound for a selected snapshot's sequence number. Default is no lower bound,
 *   i.e. `0L`
 * @param minTimestamp lower bound for a selected snapshot's timestamp. Default is no lower bound,
 *   i.e. `0L`
 *
 * @see [[Recovery]]
 */
@SerialVersionUID(1L)
final case class SnapshotSelectionCriteria(
  maxSequenceNr: Long = Long.MaxValue,
  maxTimestamp:  Long = Long.MaxValue,
  minSequenceNr: Long = 0L,
  minTimestamp:  Long = 0L) {

  /**
   * INTERNAL API.
   */
  private[persistence] def limit(toSequenceNr: Long): SnapshotSelectionCriteria =
    if (toSequenceNr < maxSequenceNr) copy(maxSequenceNr = toSequenceNr) else this

  /**
   * INTERNAL API.
   */
  private[persistence] def matches(metadata: SnapshotMetadata): Boolean =
    metadata.sequenceNr <= maxSequenceNr && metadata.timestamp <= maxTimestamp &&
      metadata.sequenceNr >= minSequenceNr && metadata.timestamp >= minTimestamp
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
  def create(maxSequenceNr: Long, maxTimestamp: Long,
             minSequenceNr: Long, minTimestamp: Long) =
    SnapshotSelectionCriteria(maxSequenceNr, maxTimestamp, minSequenceNr, minTimestamp)

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
 * Plugin API: a selected snapshot matching [[SnapshotSelectionCriteria]].
 *
 * @param metadata snapshot metadata.
 * @param snapshot snapshot.
 */
final case class SelectedSnapshot(metadata: SnapshotMetadata, snapshot: Any)

object SelectedSnapshot {
  /**
   * Java API, Plugin API.
   */
  def create(metadata: SnapshotMetadata, snapshot: Any): SelectedSnapshot =
    SelectedSnapshot(metadata, snapshot)
}

/**
 * INTERNAL API.
 *
 * Defines messages exchanged between persistent actors and a snapshot store.
 */
private[persistence] object SnapshotProtocol {

  /** Marker trait shared by internal snapshot messages. */
  sealed trait Message extends Protocol.Message
  /** Internal snapshot command. */
  sealed trait Request extends Message
  /** Internal snapshot acknowledgement. */
  sealed trait Response extends Message

  /**
   * Instructs a snapshot store to load a snapshot.
   *
   * @param persistenceId persistent actor id.
   * @param criteria criteria for selecting a snapshot from which recovery should start.
   * @param toSequenceNr upper sequence number bound (inclusive) for recovery.
   */
  final case class LoadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long)
    extends Request

  /**
   * Response message to a [[LoadSnapshot]] message.
   *
   * @param snapshot loaded snapshot, if any.
   */
  final case class LoadSnapshotResult(snapshot: Option[SelectedSnapshot], toSequenceNr: Long)
    extends Response

  /**
   * Instructs snapshot store to save a snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  final case class SaveSnapshot(metadata: SnapshotMetadata, snapshot: Any)
    extends Request

  /**
   * Instructs snapshot store to delete a snapshot.
   *
   * @param metadata snapshot metadata.
   */
  final case class DeleteSnapshot(metadata: SnapshotMetadata)
    extends Request

  /**
   * Instructs snapshot store to delete all snapshots that match `criteria`.
   *
   * @param persistenceId persistent actor id.
   * @param criteria criteria for selecting snapshots to be deleted.
   */
  final case class DeleteSnapshots(persistenceId: String, criteria: SnapshotSelectionCriteria)
    extends Request
}
