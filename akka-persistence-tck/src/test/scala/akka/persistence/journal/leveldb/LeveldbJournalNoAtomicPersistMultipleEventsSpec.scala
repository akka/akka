/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalSpec
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbJournalNoAtomicPersistMultipleEventsSpec extends JournalSpec(
  config = PersistenceSpec.config(
    "leveldb",
    "LeveldbJournalNoAtomicPersistMultipleEventsSpec",
    extraConfig = Some("akka.persistence.journal.leveldb.native = off")))
  with PluginCleanup {

  /**
   * Setting to false to test the single message atomic write behavior of JournalSpec
   */
  override def supportsAtomicPersistAllOfSeveralEvents = false

  override def supportsRejectingNonSerializableObjects = true

}

