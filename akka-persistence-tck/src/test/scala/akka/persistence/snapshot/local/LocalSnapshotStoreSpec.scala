package akka.persistence.snapshot.local

import com.typesafe.config.ConfigFactory

import akka.persistence.PluginCleanup
import akka.persistence.snapshot.SnapshotStoreSpec

class LocalSnapshotStoreSpec extends SnapshotStoreSpec with PluginCleanup {
  lazy val config = ConfigFactory.parseString(
    """
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
    """.stripMargin)

}
