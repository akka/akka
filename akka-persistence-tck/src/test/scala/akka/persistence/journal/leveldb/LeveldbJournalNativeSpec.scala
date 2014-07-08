package akka.persistence.journal.leveldb

import com.typesafe.config.ConfigFactory

import akka.persistence.journal.{ JournalPerfSpec, JournalSpec }
import akka.persistence.PluginCleanup

class LeveldbJournalNativeSpec extends JournalSpec with JournalPerfSpec with PluginCleanup {
  lazy val config = ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      |akka.persistence.journal.leveldb.native = on
      |akka.persistence.journal.leveldb.dir = "target/journal-native"
      |akka.persistence.snapshot-store.local.dir = "target/snapshots-native/"
    """.stripMargin)
}
