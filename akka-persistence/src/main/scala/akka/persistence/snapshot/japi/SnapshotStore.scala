/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.snapshot.japi

import akka.persistence._
import akka.persistence.snapshot.{ SnapshotStore ⇒ SSnapshotStore }
import akka.japi.Util._

import scala.concurrent.{ Future, ExecutionContext }

/**
 * Java API: abstract snapshot store.
 */
abstract class SnapshotStore extends SSnapshotStore with SnapshotStorePlugin {
  private implicit val ec: ExecutionContext = context.dispatcher

  override final def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    doLoadAsync(persistenceId, criteria).map(option)

  override final def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    doSaveAsync(metadata, snapshot).map(Unit.unbox)

  override final def deleteAsync(metadata: SnapshotMetadata): Future[Unit] =
    doDeleteAsync(metadata).map(_ ⇒ ())

  override final def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    doDeleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria).map(_ ⇒ ())

}
