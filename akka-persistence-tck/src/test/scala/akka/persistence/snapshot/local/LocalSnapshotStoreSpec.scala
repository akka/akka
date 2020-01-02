/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.snapshot.local

import akka.persistence.CapabilityFlag
import com.typesafe.config.ConfigFactory
import akka.persistence.PluginCleanup
import akka.persistence.snapshot.SnapshotStoreSpec

class LocalSnapshotStoreSpec
    extends SnapshotStoreSpec(
      config =
        ConfigFactory.parseString("""
    akka.test.timefactor = 3
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots"
    """))
    with PluginCleanup {

  override protected def supportsSerialization: CapabilityFlag = CapabilityFlag.on
}
