package akka.persistence.query.leveldb

import akka.persistence.query.{ AllPersistenceIdsQuerySpec, CurrentEventsByPersistenceIdQuerySpec, CurrentPersistenceIdsQuerySpec, QuerySpec }
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
  with PluginCleanup {
  override def readJournalPluginId: String = "akka.persistence.query.journal.leveldb"
}
