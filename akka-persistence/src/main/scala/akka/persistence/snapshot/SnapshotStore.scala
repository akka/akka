/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.snapshot

import scala.concurrent.Future

import akka.actor._
import akka.pattern.pipe
import akka.persistence._

/**
 * Abstract snapshot store.
 */
trait SnapshotStore extends Actor with ActorLogging {
  import SnapshotProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val publish = extension.settings.internal.publishPluginCommands

  final def receive = {
    case LoadSnapshot(persistenceId, criteria, toSequenceNr) ⇒
      val p = sender()
      loadAsync(persistenceId, criteria.limit(toSequenceNr)) map {
        sso ⇒ LoadSnapshotResult(sso, toSequenceNr)
      } recover {
        case e ⇒ LoadSnapshotResult(None, toSequenceNr)
      } pipeTo p

    case SaveSnapshot(metadata, snapshot) ⇒
      val p = sender()
      val md = metadata.copy(timestamp = System.currentTimeMillis)
      saveAsync(md, snapshot) map {
        _ ⇒ SaveSnapshotSuccess(md)
      } recover {
        case e ⇒ SaveSnapshotFailure(metadata, e)
      } to (self, p)

    case evt @ SaveSnapshotSuccess(metadata) ⇒
      try saved(metadata) finally sender() ! evt // sender is persistentActor

    case evt @ SaveSnapshotFailure(metadata, _) ⇒
      try deleteAsync(metadata) finally sender() ! evt // sender is persistentActor

    case d @ DeleteSnapshot(metadata) ⇒
      val p = sender()
      deleteAsync(metadata) map {
        case _ ⇒
          log.warning("deleting by: " + d)
          DeleteSnapshotSuccess(metadata)
      } recover {
        case e ⇒ DeleteSnapshotFailure(metadata, e)
      } pipeTo p onComplete {
        case _ if publish ⇒ context.system.eventStream.publish(d)
      }

    case d @ DeleteSnapshots(persistenceId, criteria) ⇒
      val p = sender()
      deleteAsync(persistenceId, criteria) map {
        case _ ⇒ DeleteSnapshotsSuccess(criteria)
      } recover {
        case e ⇒ DeleteSnapshotsFailure(criteria, e)
      } pipeTo p onComplete {
        case _ if publish ⇒ context.system.eventStream.publish(d)
      }
  }

  //#snapshot-store-plugin-api
  /**
   * Plugin API: asynchronously loads a snapshot.
   *
   * @param persistenceId id of the persistent actor.
   * @param criteria selection criteria for loading.
   */
  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]]

  /**
   * Plugin API: asynchronously saves a snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit]

  /**
   * Plugin API: called after successful saving of a snapshot.
   *
   * @param metadata snapshot metadata.
   */
  def saved(metadata: SnapshotMetadata): Unit

  /**
   * Plugin API: deletes the snapshot identified by `metadata`.
   *
   * @param metadata snapshot metadata.
   */

  def deleteAsync(metadata: SnapshotMetadata): Future[Unit]

  /**
   * Plugin API: deletes all snapshots matching `criteria`.
   *
   * @param persistenceId id of the persistent actor.
   * @param criteria selection criteria for deleting.
   */
  def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit]
  //#snapshot-store-plugin-api
}
