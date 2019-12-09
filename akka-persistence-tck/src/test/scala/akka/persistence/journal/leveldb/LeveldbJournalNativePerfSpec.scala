/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalPerfSpec
import akka.persistence.PersistenceSpec
import akka.persistence.PluginCleanup

class LeveldbJournalNativePerfSpec
    extends JournalPerfSpec(
      config = PersistenceSpec.config(
        "leveldb",
        "LeveldbJournalNativePerfSpec",
        extraConfig = Some("""
        akka.persistence.journal.leveldb.native = on
        akka.actor.allow-java-serialization = off
        akka.actor.warn-about-java-serializer-usage = on
        """)))
    with PluginCleanup {

  override def supportsRejectingNonSerializableObjects = true

  override def supportsSerialization = true

  override def measurementIterations: Int = 3

  override def eventsCount: Int = 1000

}
