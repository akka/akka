package akka.persistence.testkit.scaladsl


import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.snapshot.SnapshotStore
import akka.persistence._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try


class PersistenceTestKitPlugin extends AsyncWriteJournal {

  private final val storage = InMemStorageExtension(context.system)

  private implicit val ec = context.system.dispatcher

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    Future.fromTry(Try(messages.map(aw ⇒ storage.tryAdd(aw.payload))))

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
  //todo should we emulate exception on delete?
    Future.successful(storage.deleteToSeqNumber(persistenceId, toSequenceNr))

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    Future.fromTry(Try(storage.tryRead(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(recoveryCallback)))

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
  //todo should we emulate exception on readSeqNumber?
    Future.successful(storage.readHighestSequenceNum(persistenceId))

}



object PersistenceTestKitPlugin {

  val PluginId = "persistence.testkit.plugin"

  import scala.collection.JavaConverters._

  val PersitenceTestkitPluginConfig: Config = ConfigFactory.parseMap(
    Map(
      "akka.persistence.journal.plugin" -> PluginId,
      s"$PluginId.class" -> s"${classOf[PersistenceTestKitPlugin].getName}"
    ).asJava
  )

}


class PersistenceTestKitSnapshotPlugin extends SnapshotStore {


  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = ???

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = ???

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = ???

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = ???
}


