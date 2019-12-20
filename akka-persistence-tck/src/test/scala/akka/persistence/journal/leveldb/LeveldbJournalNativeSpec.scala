/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalSpec
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbJournalNativeSpec
    extends JournalSpec(
      config = PersistenceSpec.config(
        "leveldb",
        "LeveldbJournalNativeSpec",
        extraConfig = Some("""
        akka.persistence.journal.leveldb.native = on
        akka.actor.allow-java-serialization = off
        akka.actor.warn-about-java-serializer-usage = on
        """)))
    with PluginCleanup {

  override def supportsRejectingNonSerializableObjects = true

  override def supportsSerialization = true

}
