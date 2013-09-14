/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import com.typesafe.config.Config

import akka.actor._
import akka.persistence.journal._

/**
 * Akka persistence extension.
 */
object Persistence extends ExtensionId[Persistence] with ExtensionIdProvider {
  class Settings(config: Config) {
    val rootConfig = config.getConfig("akka.persistence.journal")
    val journalName = rootConfig.getString("use")
    val journalConfig = rootConfig.getConfig(journalName)
    val journalFactory = journalName match {
      case "inmem"   ⇒ new InmemJournalSettings(journalConfig)
      case "leveldb" ⇒ new LeveldbJournalSettings(journalConfig)
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
 * Akka persistence extension.
 */
class Persistence(val system: ExtendedActorSystem) extends Extension {
  private val settings = new Persistence.Settings(system.settings.config)
  private val journal = settings.journalFactory.createJournal(system)
  // TODO: journal should have its own dispatcher

  /**
   * Returns a journal for processor identified by `pid`.
   *
   * @param processorId processor id.
   */
  def journalFor(processorId: String): ActorRef = {
    // Currently returns a journal singleton is returned but this methods allows
    // for later optimisations where each processor can have its own journal actor.
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
