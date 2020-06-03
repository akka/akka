/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

import com.typesafe.config.{ Config, ConfigFactory }

import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.journal.{ AsyncWriteJournal, Tagged }
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.testkit.internal.{ InMemStorageExtension, SnapshotStorageEmulatorExtension }

/**
 * INTERNAL API
 *
 * Persistence testkit plugin for events.
 */
@InternalApi
class PersistenceTestKitPlugin extends AsyncWriteJournal {

  private final val storage = InMemStorageExtension(context.system)

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    Future.fromTry(Try(messages.map(aw => {
      val data = aw.payload.map(pl =>
        pl.payload match {
          case Tagged(p, _) => pl.withPayload(p)
          case _            => pl
        })
      storage.tryAdd(data)
    })))

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    Future.fromTry(Try(storage.tryDelete(persistenceId, toSequenceNr)))

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit): Future[Unit] =
    Future.fromTry(Try(storage.tryRead(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(recoveryCallback)))

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future.fromTry(Try {
      val found = storage.tryReadSeqNumber(persistenceId)
      if (found < fromSequenceNr) fromSequenceNr else found
    })

}

object PersistenceTestKitPlugin {

  val PluginId = "akka.persistence.testkit.journal.pluginid"

  import akka.util.ccompat.JavaConverters._

  def getInstance() = this

  val config: Config = ConfigFactory.parseMap(
    Map(
      "akka.persistence.journal.plugin" -> PluginId,
      s"$PluginId.class" -> s"${classOf[PersistenceTestKitPlugin].getName}").asJava)

}

/**
 * INTERNAL API
 *
 * Persistence testkit plugin for snapshots.
 */
@InternalApi
class PersistenceTestKitSnapshotPlugin extends SnapshotStore {

  private final val storage = SnapshotStorageEmulatorExtension(context.system)

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future.fromTry(Try(storage.tryRead(persistenceId, criteria)))

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    Future.fromTry(Try(storage.tryAdd(metadata, snapshot)))

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] =
    Future.fromTry(Try(storage.tryDelete(metadata)))

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    Future.successful(Try(storage.tryDelete(persistenceId, criteria)))

}

object PersistenceTestKitSnapshotPlugin {

  val PluginId = "akka.persistence.testkit.snapshotstore.pluginid"

  import akka.util.ccompat.JavaConverters._

  def getInstance() = this

  val config: Config = ConfigFactory.parseMap(
    Map(
      "akka.persistence.snapshot-store.plugin" -> PluginId,
      s"$PluginId.class" -> classOf[PersistenceTestKitSnapshotPlugin].getName).asJava)

}
