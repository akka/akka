package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalSpec
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbJournalNativeSpec extends JournalSpec with PluginCleanup {
  lazy val config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalNativeSpec",
    extraConfig = Some("akka.persistence.journal.leveldb.native = on"))

}
