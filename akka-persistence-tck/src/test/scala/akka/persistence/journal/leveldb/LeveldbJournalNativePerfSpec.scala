/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.journal.leveldb

import akka.persistence.journal.{ JournalPerfSpec }
import akka.persistence.{ PersistenceSpec, PluginCleanup }
import org.scalatest.DoNotDiscover

@DoNotDiscover // because only checking that compilation is OK with JournalPerfSpec
class LeveldbJournalNativePerfSpec extends JournalPerfSpec(
  config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalNativePerfSpec",
    extraConfig = Some("akka.persistence.journal.leveldb.native = on")))
  with PluginCleanup {

  override def supportsRejectingNonSerializableObjects = true

}
