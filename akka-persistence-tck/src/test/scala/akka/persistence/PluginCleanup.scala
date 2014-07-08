package akka.persistence

import java.io.File

import org.apache.commons.io.FileUtils

trait PluginCleanup extends PluginSpec {
  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override def beforeAll() {
    storageLocations.foreach(FileUtils.deleteDirectory)
    super.beforeAll()
  }

  override def afterAll() {
    super.afterAll()
    storageLocations.foreach(FileUtils.deleteDirectory)
  }
}
