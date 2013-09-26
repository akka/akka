/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence

import java.io._

import akka.actor._
import akka.util.ClassLoaderObjectInputStream

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
 * Indicates successful saving of a snapshot.
 *
 * @param metadata snapshot metadata.
 */
@SerialVersionUID(1L)
case class SaveSnapshotSucceeded(metadata: SnapshotMetadata)

/**
 * Indicates failed saving of a snapshot.
 *
 * @param metadata snapshot metadata.
 * @param reason failure reason.
 */
@SerialVersionUID(1L)
case class SaveSnapshotFailed(metadata: SnapshotMetadata, reason: Throwable)

/**
 * Offers a [[Processor]] a previously saved `snapshot` during recovery. This offer is received
 * before any further replayed messages.
 */
@SerialVersionUID(1L)
case class SnapshotOffer(metadata: SnapshotMetadata, snapshot: Any)

/**
 * Snapshot selection criteria for recovery.
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

// TODO: support application-defined snapshot serializers
// TODO: support application-defined snapshot access

/**
 * Snapshot serialization extension.
 */
private[persistence] object SnapshotSerialization extends ExtensionId[SnapshotSerialization] with ExtensionIdProvider {
  def createExtension(system: ExtendedActorSystem): SnapshotSerialization = new SnapshotSerialization(system)
  def lookup() = SnapshotSerialization
}

/**
 * Snapshot serialization extension.
 */
private[persistence] class SnapshotSerialization(val system: ExtendedActorSystem) extends Extension {
  import akka.serialization.JavaSerializer

  /**
   * Java serialization based snapshot serializer.
   */
  val java = new SnapshotSerializer {
    def serialize(stream: OutputStream, metadata: SnapshotMetadata, state: Any) = {
      val out = new ObjectOutputStream(stream)
      JavaSerializer.currentSystem.withValue(system) { out.writeObject(state) }
    }

    def deserialize(stream: InputStream, metadata: SnapshotMetadata) = {
      val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader, stream)
      JavaSerializer.currentSystem.withValue(system) { in.readObject }
    }
  }
}

/**
 * Stream-based snapshot serializer.
 */
private[persistence] trait SnapshotSerializer {
  /**
   * Serializes a `snapshot` to an output stream.
   */
  def serialize(stream: OutputStream, metadata: SnapshotMetadata, snapshot: Any): Unit

  /**
   * Deserializes a snapshot from an input stream.
   */
  def deserialize(stream: InputStream, metadata: SnapshotMetadata): Any
}

/**
 * Input and output stream management for snapshot serialization.
 */
private[persistence] trait SnapshotAccess {
  /**
   * Provides a managed output stream for serializing a snapshot.
   *
   * @param metadata snapshot metadata needed to create an output stream.
   * @param body called with the managed output stream as argument.
   */
  def withOutputStream(metadata: SnapshotMetadata)(body: OutputStream ⇒ Unit)

  /**
   * Provides a managed input stream for deserializing a state object.
   *
   * @param metadata snapshot metadata needed to create an input stream.
   * @param body called with the managed input stream as argument.
   * @return read snapshot.
   */
  def withInputStream(metadata: SnapshotMetadata)(body: InputStream ⇒ Any): Any

  /**
   * Loads the snapshot metadata of all currently stored snapshots.
   */
  def metadata: Set[SnapshotMetadata]

  /**
   * Deletes the snapshot referenced by `metadata`.
   */
  def delete(metadata: SnapshotMetadata)
}

private[persistence] trait SnapshotStoreFactory {
  /**
   * Creates a new snapshot store actor.
   */
  def createSnapshotStore(implicit factory: ActorRefFactory): ActorRef
}

private[persistence] object SnapshotStore {
  /**
   * Instructs a snapshot store to load a snapshot.
   *
   * @param processorId processor id.
   * @param criteria criteria for selecting a saved snapshot from which recovery should start.
   * @param toSequenceNr upper sequence number bound (inclusive) for recovery.
   */
  case class LoadSnapshot(processorId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long)

  /**
   * Reply message to a processor that a snapshot loading attempt has been completed.
   *
   * @param savedSnapshot
   */
  case class LoadSnapshotCompleted(savedSnapshot: Option[SavedSnapshot], toSequenceNr: Long)

  /**
   * Instructs snapshot store to save a snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  case class SaveSnapshot(metadata: SnapshotMetadata, snapshot: Any)

  /**
   * In-memory representation of a saved snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot saved snapshot.
   */
  case class SavedSnapshot(metadata: SnapshotMetadata, snapshot: Any)
}
