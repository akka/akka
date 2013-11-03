/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence

//#plugin-imports
import scala.concurrent.Future
import scala.collection.immutable.Seq
//#plugin-imports

import com.typesafe.config._

import org.scalatest.WordSpec

import akka.actor.ActorSystem
//#plugin-imports
import akka.persistence._
import akka.persistence.journal._
import akka.persistence.snapshot._
//#plugin-imports

object PersistencePluginDocSpec {
  val config =
    """
      //#journal-config
      akka.persistence.journal.leveldb.dir = "target/journal"
      //#journal-config
      //#snapshot-config
      akka.persistence.snapshot-store.local.dir = "target/snapshots"
      //#snapshot-config
    """
}

class PersistencePluginDocSpec extends WordSpec {
  new AnyRef {
    val providerConfig =
      """
        //#journal-plugin-config
        # Path to the journal plugin to be used
        akka.persistence.journal.plugin = "my-journal"

        # My custom journal plugin
        my-journal {
          # Class name of the plugin.
          class = "docs.persistence.MyJournal"
          # Dispatcher for the plugin actor.
          plugin-dispatcher = "akka.actor.default-dispatcher"
        }
        //#journal-plugin-config

        //#snapshot-store-plugin-config
        # Path to the snapshot store plugin to be used
        akka.persistence.snapshot-store.plugin = "my-snapshot-store"

        # My custom snapshot store plugin
        my-snapshot-store {
          # Class name of the plugin.
          class = "docs.persistence.MySnapshotStore"
          # Dispatcher for the plugin actor.
          plugin-dispatcher = "akka.persistence.dispatchers.default-plugin-dispatcher"
        }
        //#snapshot-store-plugin-config
      """

    val system = ActorSystem("doc", ConfigFactory.parseString(providerConfig).withFallback(ConfigFactory.parseString(PersistencePluginDocSpec.config)))
    val extension = Persistence(system)
  }
}

class MyJournal extends AsyncWriteJournal {
  def writeAsync(persistent: PersistentImpl): Future[Unit] = ???
  def writeBatchAsync(persistentBatch: Seq[PersistentImpl]): Future[Unit] = ???
  def deleteAsync(persistent: PersistentImpl): Future[Unit] = ???
  def confirmAsync(processorId: String, sequenceNr: Long, channelId: String): Future[Unit] = ???
  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentImpl) ⇒ Unit): Future[Long] = ???
}

class MySnapshotStore extends SnapshotStore {
  def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = ???
  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = ???
  def saved(metadata: SnapshotMetadata) {}
  def delete(metadata: SnapshotMetadata) {}
}
