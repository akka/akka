/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.snapshot.SnapshotStore
import akka.persistence._
import com.typesafe.config.{ Config, ConfigFactory }

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

class PersistenceTestKitPlugin extends AsyncWriteJournal {

  private final val storage = InMemStorageExtension(context.system)

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    Future.fromTry(Try(messages.map(aw ⇒ storage.tryAdd(aw.payload))))

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    //todo should we emulate exception on delete?
    Future.successful(storage.deleteToSeqNumber(persistenceId, toSequenceNr))

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    Future.fromTry(Try(storage.tryRead(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(recoveryCallback)))

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    //todo should we emulate exception on readSeqNumber?
    val found = storage.reloadHighestSequenceNum(persistenceId)
    val sn = if (found < fromSequenceNr) fromSequenceNr else found
    Future.successful(sn)
  }

}

object PersistenceTestKitPlugin {

  val PluginId = "akka.persistence.testkit.journal.pluginid"

  import scala.collection.JavaConverters._

  val PersitenceTestkitJournalConfig: Config = ConfigFactory.parseMap(
    Map(
      "akka.persistence.journal.plugin" -> PluginId,
      s"$PluginId.class" -> s"${classOf[PersistenceTestKitPlugin].getName}"
    ).asJava
  )

}

class PersistenceTestKitSnapshotPlugin extends SnapshotStore {

  private val storage = SnapShotStorageEmulatorExtension(context.system)

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future.fromTry(Try(storage.tryRead(persistenceId, criteria)))

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    Future.fromTry(Try(storage.tryAdd(metadata, snapshot)))

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] =
    //todo do we need to emulate delete failure?
    Future.successful(storage.delete(metadata.persistenceId, _._1.sequenceNr == metadata.sequenceNr))

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    //todo do we need to emulate delete failure?
    Future.successful(storage.delete(persistenceId, v ⇒ criteria.matches(v._1)))

}

object PersistenceTestKitSnapshotPlugin {

  val PluginId = "akka.persistence.testkit.snapshotstore.pluginid"

  import scala.collection.JavaConverters._

  val PersitenceTestkitSnapshotStoreConfig: Config = ConfigFactory.parseMap(
    Map(
      "akka.persistence.snapshot-store.plugin" -> PluginId,
      s"$PluginId.class" -> s"${classOf[PersistenceTestKitSnapshotPlugin].getName}"
    ).asJava
  )

}

