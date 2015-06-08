/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query

import java.util.concurrent.atomic.AtomicReference

import akka.actor._
import akka.event.Logging

import scala.annotation.tailrec
import scala.util.Failure

/**
 * Persistence extension for queries.
 */
object PersistenceQuery extends ExtensionId[PersistenceQuery] with ExtensionIdProvider {
  /**
   * Java API.
   */
  override def get(system: ActorSystem): PersistenceQuery = super.get(system)

  def createExtension(system: ExtendedActorSystem): PersistenceQuery = new PersistenceQuery(system)

  def lookup() = PersistenceQuery

  /** INTERNAL API. */
  private[persistence] case class PluginHolder(plugin: scaladsl.ReadJournal) extends Extension

}

class PersistenceQuery(system: ExtendedActorSystem) extends Extension {
  import PersistenceQuery._

  private val log = Logging(system, getClass)

  /** Discovered query plugins. */
  private val readJournalPluginExtensionIds = new AtomicReference[Map[String, ExtensionId[PluginHolder]]](Map.empty)

  /**
   * Returns the [[akka.persistence.query.scaladsl.ReadJournal]] specified by the given read journal configuration entry.
   */
  @tailrec final def readJournalFor(readJournalPluginId: String): scaladsl.ReadJournal = {
    val configPath = readJournalPluginId
    val extensionIdMap = readJournalPluginExtensionIds.get
    extensionIdMap.get(configPath) match {
      case Some(extensionId) ⇒
        extensionId(system).plugin
      case None ⇒
        val extensionId = new ExtensionId[PluginHolder] {
          override def createExtension(system: ExtendedActorSystem): PluginHolder =
            PluginHolder(createPlugin(configPath))
        }
        readJournalPluginExtensionIds.compareAndSet(extensionIdMap, extensionIdMap.updated(configPath, extensionId))
        readJournalFor(readJournalPluginId) // Recursive invocation.
    }
  }

  /**
   * Java API
   *
   * Returns the [[akka.persistence.query.javadsl.ReadJournal]] specified by the given read journal configuration entry.
   */
  final def getReadJournalFor(readJournalPluginId: String): javadsl.ReadJournal =
    new javadsl.ReadJournal(readJournalFor(readJournalPluginId))

  private def createPlugin(configPath: String): scaladsl.ReadJournal = {
    require(!isEmpty(configPath) && system.settings.config.hasPath(configPath),
      s"'reference.conf' is missing persistence read journal plugin config path: '${configPath}'")
    val pluginActorName = configPath
    val pluginConfig = system.settings.config.getConfig(configPath)
    val pluginClassName = pluginConfig.getString("class")
    log.debug(s"Create plugin: ${pluginActorName} ${pluginClassName}")
    val pluginClass = system.dynamicAccess.getClassFor[AnyRef](pluginClassName).get

    val plugin = system.dynamicAccess.createInstanceFor[scaladsl.ReadJournal](pluginClass, (classOf[ExtendedActorSystem], system) :: Nil)
      .orElse(system.dynamicAccess.createInstanceFor[scaladsl.ReadJournal](pluginClass, Nil))
      .recoverWith {
        case ex: Exception ⇒ Failure.apply(new IllegalArgumentException(s"Unable to create read journal plugin instance for path [$configPath], class [$pluginClassName]!", ex))
      }

    // TODO possibly apply event adapters here
    plugin.get
  }

  /** Check for default or missing identity. */
  private def isEmpty(text: String) = text == null || text.length == 0
}

