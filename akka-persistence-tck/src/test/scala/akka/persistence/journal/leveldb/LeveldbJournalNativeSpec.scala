package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalSpec
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbJournalNativeSpec extends JournalSpec(
  config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalNativeSpec",
    extraConfig = Some("akka.persistence.journal.leveldb.native = on")))
  with PluginCleanup {

  override def supportsRejectingNonSerializableObjects = true

}
