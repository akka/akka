package akka.persistence.query.leveldb

import akka.persistence.query._
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbQuerySpec extends QuerySpec(
  config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalJavaSpec",
    extraConfig = Some(
      """
        |akka.persistence.journal.leveldb.native = off
        |akka.persistence.query.journal.leveldb.refresh-interval = 300ms
      """.stripMargin)))
  with CurrentPersistenceIdsQuerySpec
  with AllPersistenceIdsQuerySpec
  with CurrentEventsByPersistenceIdQuerySpec
  with EventsByPersistenceIdQuerySpec
  with CurrentEventsByTagQuerySpec
  with EventsByTagQuerySpec
  with PluginCleanup {
  override def readJournalPluginId: String = "akka.persistence.query.journal.leveldb"
}
