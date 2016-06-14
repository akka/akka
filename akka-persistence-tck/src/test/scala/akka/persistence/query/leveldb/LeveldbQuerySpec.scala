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
      """.stripMargin)))
  with CurrentPersistenceIdsQuerySpec
  with AllPersistenceIdsQuerySpec
  with CurrentEventsByPersistenceIdQuerySpec
  with EventsByPersistenceIdQuerySpec
  with PluginCleanup {
  override def readJournalPluginId: String = "akka.persistence.query.journal.leveldb"
}
