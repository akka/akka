/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.snapshot.japi

import scala.concurrent.Future

import akka.japi.{ Option ⇒ JOption }
import akka.persistence._
import akka.persistence.snapshot.{ SnapshotStore ⇒ SSnapshotStore }

/**
 * Java API.
 *
 * Abstract snapshot store.
 */
abstract class SnapshotStore extends SSnapshotStore with SnapshotStorePlugin {
  import context.dispatcher

  final def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria) =
    doLoadAsync(processorId, criteria).map(_.asScala)

  final def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    doSaveAsync(metadata, snapshot).map(Unit.unbox)

  final def saved(metadata: SnapshotMetadata) =
    onSaved(metadata)

  final def delete(metadata: SnapshotMetadata) =
    doDelete(metadata)
}
