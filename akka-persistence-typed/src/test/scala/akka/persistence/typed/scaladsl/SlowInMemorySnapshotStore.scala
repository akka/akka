/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.persistence.SelectedSnapshot
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.typed.scaladsl.SnapshotMutableStateSpec.MutableState
import akka.persistence.{ SnapshotSelectionCriteria => ClassicSnapshotSelectionCriteria }
import akka.persistence.{ SnapshotMetadata => ClassicSnapshotMetadata }

import scala.concurrent.Future

class SlowInMemorySnapshotStore extends SnapshotStore {

  private var state = Map.empty[String, (Any, ClassicSnapshotMetadata)]

  def loadAsync(persistenceId: String, criteria: ClassicSnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    Future.successful(state.get(persistenceId).map {
      case (snap, meta) => SelectedSnapshot(meta, snap)
    })
  }

  def saveAsync(metadata: ClassicSnapshotMetadata, snapshot: Any): Future[Unit] = {
    val snapshotState = snapshot.asInstanceOf[MutableState]
    val value1 = snapshotState.value
    Thread.sleep(50)
    val value2 = snapshotState.value
    // it mustn't have been modified by another command/event
    if (value1 != value2)
      Future.failed(new IllegalStateException(s"State changed from $value1 to $value2"))
    else {
      // copy to simulate serialization, and subsequent recovery shouldn't get same instance
      state = state.updated(metadata.persistenceId, (new MutableState(snapshotState.value), metadata))
      Future.successful(())
    }
  }

  override def deleteAsync(metadata: ClassicSnapshotMetadata): Future[Unit] = {
    state = state.filterNot {
      case (pid, (_, meta)) => pid == metadata.persistenceId && meta.sequenceNr == metadata.sequenceNr
    }
    Future.successful(())
  }

  override def deleteAsync(persistenceId: String, criteria: ClassicSnapshotSelectionCriteria): Future[Unit] = {
    state = state.filterNot {
      case (pid, (_, meta)) => pid == persistenceId && criteria.matches(meta)
    }
    Future.successful(())
  }
}
