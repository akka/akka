/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.journal.leveldb

import akka.persistence.journal.{ JournalPerfSpec, JournalSpec }
import akka.persistence.{ CapabilityFlag, PersistenceSpec, PluginCleanup }
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
