/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

import akka.persistence.PersistentActor

object PersistenceMultiDocSpec {

  val DefaultConfig = """
  //#default-config
  # Absolute path to the default journal plugin configuration entry.
  akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
  # Absolute path to the default snapshot store plugin configuration entry.
  akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
  //#default-config
  """

  //#default-plugins
  trait ActorWithDefaultPlugins extends PersistentActor {
    override def persistenceId = "123"
  }
  //#default-plugins

  val OverrideConfig = s"""
  //#override-config
  # Configuration entry for the custom journal plugin, see `journalPluginId`.
  akka.persistence.chronicle.journal {
    # Standard persistence extension property: provider FQCN.
    class = "akka.persistence.chronicle.ChronicleSyncJournal"
    # Custom setting specific for the journal `ChronicleSyncJournal`.
    folder = $${user.dir}/store/journal
  }
  # Configuration entry for the custom snapshot store plugin, see `snapshotPluginId`.
  akka.persistence.chronicle.snapshot-store {
    # Standard persistence extension property: provider FQCN.
    class = "akka.persistence.chronicle.ChronicleSnapshotStore"
    # Custom setting specific for the snapshot store `ChronicleSnapshotStore`.
    folder = $${user.dir}/store/snapshot
  }
  //#override-config
  """

  //#override-plugins
  trait ActorWithOverridePlugins extends PersistentActor {
    override def persistenceId = "123"
    // Absolute path to the journal plugin configuration entry in the `reference.conf`.
    override def journalPluginId = "akka.persistence.chronicle.journal"
    // Absolute path to the snapshot store plugin configuration entry in the `reference.conf`.
    override def snapshotPluginId = "akka.persistence.chronicle.snapshot-store"
  }
  //#override-plugins

}
