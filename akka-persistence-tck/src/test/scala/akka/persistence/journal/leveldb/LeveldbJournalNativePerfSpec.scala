/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.leveldb

import akka.persistence.PersistenceSpec
import akka.persistence.PluginCleanup
import akka.persistence.journal.JournalPerfSpec

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

  // increase for real measurements, low because of slow CI
  override def measurementIterations: Int = 3

  // increase for real measurements, low because of slow CI
  override def eventsCount: Int = 100

}
