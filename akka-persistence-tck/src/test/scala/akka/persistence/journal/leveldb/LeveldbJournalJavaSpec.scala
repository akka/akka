/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.leveldb

import akka.persistence.journal.JournalSpec
import akka.persistence.{ PersistenceSpec, PluginCleanup }

class LeveldbJournalJavaSpec
    extends JournalSpec(
      config = PersistenceSpec.config(
        "leveldb",
        "LeveldbJournalJavaSpec",
        extraConfig = Some("""
        akka.persistence.journal.leveldb.native = off
        akka.actor.allow-java-serialization = off
        akka.actor.warn-about-java-serializer-usage = on
        """)))
    with PluginCleanup {

  override def supportsRejectingNonSerializableObjects = true

  override def supportsSerialization = true
}
