package akka.persistence.query.leveldb

import akka.actor.ActorRef
import akka.persistence.query._
import akka.persistence.{ CapabilityFlag, PersistenceSpec, PluginCleanup }

class LeveldbQuerySpec extends QuerySpec(
  config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalJavaSpec",
    extraConfig = Some(
      """
        |akka.persistence.journal.leveldb.native = off
      """.stripMargin)))
  with CurrentPersistenceIdsQuerySpec
  with AllPersistenceIdsQuerySpec
  with CurrentEventsByPersistenceIdQuerySpec
  with EventsByPersistenceIdQuerySpec
  with CurrentEventsByTagQuerySpec
  with EventsByTagQuerySpec
  with PluginCleanup {
  override def readJournalPluginId: String = "akka.persistence.query.journal.leveldb"

  override protected def supportsOrderingByDateIndependentlyOfPersistenceId: CapabilityFlag = true

  override protected def supportsOrderingByPersistenceIdAndSequenceNr: CapabilityFlag = false
}
