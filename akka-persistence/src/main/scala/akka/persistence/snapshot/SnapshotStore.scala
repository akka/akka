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

  final def receive = receiveSnapshotStore.orElse[Any, Unit](receivePluginInternal)

  final val receiveSnapshotStore: Actor.Receive = {
    case LoadSnapshot(persistenceId, criteria, toSequenceNr) ⇒
      loadAsync(persistenceId, criteria.limit(toSequenceNr)) map {
        sso ⇒ LoadSnapshotResult(sso, toSequenceNr)
      } recover {
        case e ⇒ LoadSnapshotResult(None, toSequenceNr)
      } pipeTo senderPersistentActor()

    case SaveSnapshot(metadata, snapshot) ⇒
      val md = metadata.copy(timestamp = System.currentTimeMillis)
      saveAsync(md, snapshot) map {
        _ ⇒ SaveSnapshotSuccess(md)
      } recover {
        case e ⇒ SaveSnapshotFailure(metadata, e)
      } to (self, senderPersistentActor())

    case evt: SaveSnapshotSuccess ⇒
      try tryReceivePluginInternal(evt) finally senderPersistentActor ! evt // sender is persistentActor
    case evt @ SaveSnapshotFailure(metadata, _) ⇒
      try {
        tryReceivePluginInternal(evt)
        deleteAsync(metadata)
      } finally senderPersistentActor() ! evt // sender is persistentActor

    case d @ DeleteSnapshot(metadata) ⇒
      deleteAsync(metadata).map {
        case _ ⇒ DeleteSnapshotSuccess(metadata)
      }.recover {
        case e ⇒ DeleteSnapshotFailure(metadata, e)
      }.pipeTo(self)(senderPersistentActor()).onComplete {
        case _ ⇒ if (publish) context.system.eventStream.publish(d)
      }

    case evt: DeleteSnapshotSuccess ⇒
      try tryReceivePluginInternal(evt) finally senderPersistentActor() ! evt
    case evt: DeleteSnapshotFailure ⇒
      try tryReceivePluginInternal(evt) finally senderPersistentActor() ! evt

    case d @ DeleteSnapshots(persistenceId, criteria) ⇒
      deleteAsync(persistenceId, criteria).map {
        case _ ⇒ DeleteSnapshotsSuccess(criteria)
      }.recover {
        case e ⇒ DeleteSnapshotsFailure(criteria, e)
      }.pipeTo(self)(senderPersistentActor()).onComplete {
        case _ ⇒ if (publish) context.system.eventStream.publish(d)
      }

    case evt: DeleteSnapshotsFailure ⇒
      try tryReceivePluginInternal(evt) finally senderPersistentActor() ! evt // sender is persistentActor
    case evt: DeleteSnapshotsSuccess ⇒
      try tryReceivePluginInternal(evt) finally senderPersistentActor() ! evt
  }

  /** Documents intent that the sender() is expected to be the PersistentActor */
  @inline private final def senderPersistentActor(): ActorRef = sender()

  private def tryReceivePluginInternal(evt: Any): Unit =
    if (receivePluginInternal.isDefinedAt(evt)) receivePluginInternal(evt)

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

  /**
   * Plugin API
   * Allows plugin implementers to use `f pipeTo self` and
   * handle additional messages for implementing advanced features
   */
  def receivePluginInternal: Actor.Receive = Actor.emptyBehavior
  //#snapshot-store-plugin-api
}
