/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

import akka.actor._
import akka.annotation.InternalApi
import akka.persistence.query.scaladsl.ReadJournal
import akka.persistence.{ Plugin, PluginProvider }
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * Persistence extension for queries.
 */
object PersistenceQuery extends ExtensionId[PersistenceQuery] with ExtensionIdProvider {
  /**
   * Java API.
   */
  override def get(system: ActorSystem): PersistenceQuery = super.get(system)

  def createExtension(system: ExtendedActorSystem): PersistenceQuery = new PersistenceQuery(system)

  def lookup(): PersistenceQuery.type = PersistenceQuery

  @InternalApi
  private[akka] implicit val pluginProvider = new PluginProvider[ReadJournalProvider, scaladsl.ReadJournal, javadsl.ReadJournal] {
    override def scalaDsl(t: ReadJournalProvider): ReadJournal = t.scaladslReadJournal()
    override def javaDsl(t: ReadJournalProvider): javadsl.ReadJournal = t.javadslReadJournal()
  }

}

import PersistenceQuery.pluginProvider

class PersistenceQuery(system: ExtendedActorSystem) extends Plugin[scaladsl.ReadJournal, javadsl.ReadJournal, ReadJournalProvider](system) with Extension {
  /**
   * Scala API: Returns the [[akka.persistence.query.scaladsl.ReadJournal]] specified by the given
   * read journal configuration entry.
   *
   * The provided readJournalPluginConfig will be used to configure the journal plugin instead of the actor system
   * config.
   */
  final def readJournalFor[T <: scaladsl.ReadJournal](readJournalPluginId: String, readJournalPluginConfig: Config): T =
    pluginFor(readJournalPluginId, readJournalPluginConfig).scaladslPlugin.asInstanceOf[T]

  /**
   * Scala API: Returns the [[akka.persistence.query.scaladsl.ReadJournal]] specified by the given
   * read journal configuration entry.
   */
  final def readJournalFor[T <: scaladsl.ReadJournal](readJournalPluginId: String): T =
    readJournalFor(readJournalPluginId, ConfigFactory.empty)

  /**
   * Java API: Returns the [[akka.persistence.query.javadsl.ReadJournal]] specified by the given
   * read journal configuration entry.
   */
  final def getReadJournalFor[T <: javadsl.ReadJournal](clazz: Class[T], readJournalPluginId: String, readJournalPluginConfig: Config): T =
    pluginFor(readJournalPluginId, readJournalPluginConfig).javadslPlugin.asInstanceOf[T]

  final def getReadJournalFor[T <: javadsl.ReadJournal](clazz: Class[T], readJournalPluginId: String): T = getReadJournalFor[T](clazz, readJournalPluginId, ConfigFactory.empty())

}

