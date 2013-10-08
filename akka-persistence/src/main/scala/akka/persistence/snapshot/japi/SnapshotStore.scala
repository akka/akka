/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.snapshot.japi

import scala.concurrent.Future

import akka.japi.{ Option ⇒ JOption }
import akka.persistence._
import akka.persistence.snapshot.{ SnapshotStore ⇒ SSnapshotStore }

abstract class SnapshotStore extends SSnapshotStore {
  import context.dispatcher

  final def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria) =
    doLoadAsync(processorId, criteria).map(_.asScala)

  final def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    doSaveAsync(metadata, snapshot).map(Unit.unbox)

  final def saved(metadata: SnapshotMetadata) =
    onSaved(metadata)

  final def delete(metadata: SnapshotMetadata) =
    doDelete(metadata)

  /**
   * Plugin Java API.
   *
   * Asynchronously loads a snapshot.
   *
   * @param processorId processor id.
   * @param criteria selection criteria for loading.
   */
  def doLoadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[JOption[SelectedSnapshot]]

  /**
   * Plugin Java API.
   *
   * Asynchronously saves a snapshot.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  def doSaveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Void]

  /**
   * Plugin Java API.
   *
   * Called after successful saving of a snapshot.
   *
   * @param metadata snapshot metadata.
   */
  @throws(classOf[Exception])
  def onSaved(metadata: SnapshotMetadata): Unit

  /**
   * Plugin Java API.
   *
   * Deletes the snapshot identified by `metadata`.
   *
   * @param metadata snapshot metadata.
   */
  @throws(classOf[Exception])
  def doDelete(metadata: SnapshotMetadata): Unit
}
