/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.snapshot.japi

import akka.japi.{ Option ⇒ JOption }
import akka.persistence._
import akka.persistence.snapshot.{ SnapshotStore ⇒ SSnapshotStore }

import scala.concurrent.Future

/**
 * Java API: abstract snapshot store.
 */
abstract class SnapshotStore extends SSnapshotStore with SnapshotStorePlugin {
  import context.dispatcher

  final def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria) =
    doLoadAsync(persistenceId, criteria).map(_.asScala)

  final def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    doSaveAsync(metadata, snapshot).map(Unit.unbox)

  final def saved(metadata: SnapshotMetadata): Unit =
    onSaved(metadata)

  final def delete(metadata: SnapshotMetadata): Future[Unit] =
    doDelete(metadata).map(_ ⇒ ())

  final def delete(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    doDelete(persistenceId: String, criteria: SnapshotSelectionCriteria).map(_ ⇒ ())

}
