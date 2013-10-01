/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import com.typesafe.config.Config

import akka.actor._
import akka.persistence.journal.leveldb._
import akka.persistence.snapshot.local._

/**
 * Persistence extension.
 */
object Persistence extends ExtensionId[Persistence] with ExtensionIdProvider {
  class Settings(config: Config) {
    val rootConfig = config.getConfig("akka.persistence")

    val journalsConfig = rootConfig.getConfig("journal")
    val journalName = journalsConfig.getString("use")
    val journalConfig = journalsConfig.getConfig(journalName)
    val journalFactory = journalName match {
      case "leveldb" ⇒ new LeveldbJournalSettings(journalConfig)
    }

    val snapshotStoresConfig = rootConfig.getConfig("snapshot-store")
    val snapshotStoreName = snapshotStoresConfig.getString("use")
    val snapshotStoreConfig = snapshotStoresConfig.getConfig(snapshotStoreName)
    val snapshotStoreFactory = snapshotStoreName match {
      case "local" ⇒ new LocalSnapshotStoreSettings(snapshotStoreConfig)
    }
  }

  /**
   * Java API.
   */
  override def get(system: ActorSystem): Persistence = super.get(system)

  def createExtension(system: ExtendedActorSystem): Persistence = new Persistence(system)

  def lookup() = Persistence
}

/**
 * Persistence extension.
 */
class Persistence(val system: ExtendedActorSystem) extends Extension {
  private val settings = new Persistence.Settings(system.settings.config)
  private val journal = settings.journalFactory.createJournal(system)
  private val snapshotStore = settings.snapshotStoreFactory.createSnapshotStore(system)

  /**
   * Returns a snapshot store for a processor identified by `processorId`.
   */
  def snapshotStoreFor(processorId: String): ActorRef = {
    // Currently returns a snapshot store singleton but this methods allows for later
    // optimizations where each processor can have its own snapshot store actor.
    snapshotStore
  }

  /**
   * Returns a journal for a processor identified by `processorId`.
   */
  def journalFor(processorId: String): ActorRef = {
    // Currently returns a journal singleton but this methods allows for later
    // optimizations where each processor can have its own journal actor.
    journal
  }

  /**
   * Creates a canonical processor id from a processor actor ref.
   */
  def processorId(processor: ActorRef): String = id(processor)

  /**
   * Creates a canonical channel id from a channel actor ref.
   */
  def channelId(channel: ActorRef): String = id(channel)

  private def id(ref: ActorRef) = ref.path.toStringWithAddress(system.provider.getDefaultAddress)
}
