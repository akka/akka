/**
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.snapshot

import scala.concurrent.Future
import scala.util._

import akka.actor._
import akka.pattern.pipe
import akka.persistence._

/**
 * Abstract snapshot store.
 */
trait SnapshotStore extends Actor {
  import SnapshotProtocol._
  import context.dispatcher

  final def receive = {
    case LoadSnapshot(processorId, criteria, toSequenceNr) ⇒ {
      val p = sender
      loadAsync(processorId, criteria.limit(toSequenceNr)) map {
        sso ⇒ LoadSnapshotResult(sso, toSequenceNr)
      } recover {
        case e ⇒ LoadSnapshotResult(None, toSequenceNr)
      } pipeTo (p)
    }
    case SaveSnapshot(metadata, snapshot) ⇒ {
      val p = sender
      val md = metadata.copy(timestamp = System.currentTimeMillis)
      saveAsync(md, snapshot) map {
        _ ⇒ SaveSnapshotSuccess(md)
      } recover {
        case e ⇒ SaveSnapshotFailure(metadata, e)
      } to (self, p)
    }
    case evt @ SaveSnapshotSuccess(metadata) ⇒ {
      saved(metadata)
      sender ! evt // sender is processor
    }
    case evt @ SaveSnapshotFailure(metadata, _) ⇒ {
      delete(metadata)
      sender ! evt // sender is processor
    }
  }

  //#snapshot-store-plugin-api
  /**
   * Plugin API.
   *
   * Asynchronously loads a snapshot.
   *
   * @param processorId processor id.
   * @param criteria selection criteria for loading.
   */
  def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]]

  /**
   * Plugin API.
   *
   * Asynchronously saves a snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit]

  /**
   * Plugin API.
   *
   * Called after successful saving of a snapshot.
   *
   * @param metadata snapshot metadata.
   */
  def saved(metadata: SnapshotMetadata)

  /**
   * Plugin API.
   *
   * Deletes the snapshot identified by `metadata`.
   *
   * @param metadata snapshot metadata.
   */
  def delete(metadata: SnapshotMetadata)
  //#snapshot-store-plugin-api
}
