/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalSpec
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbJournalJavaSpec extends JournalSpec(
  config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalJavaSpec",
    extraConfig = Some("akka.persistence.journal.leveldb.native = off")))
  with PluginCleanup {

  override def supportsRejectingNonSerializableObjects = true
}
