/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.query

import java.util.concurrent.atomic.AtomicReference
import akka.actor._
import akka.event.Logging
import scala.annotation.tailrec
import scala.util.Failure
import com.typesafe.config.Config

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
  private[persistence] case class PluginHolder(
    scaladslPlugin: scaladsl.ReadJournal, javadslPlugin: akka.persistence.query.javadsl.ReadJournal)
    extends Extension

}

class PersistenceQuery(system: ExtendedActorSystem) extends Extension {
  import PersistenceQuery._

  private val log = Logging(system, getClass)

  /** Discovered query plugins. */
  private val readJournalPluginExtensionIds = new AtomicReference[Map[String, ExtensionId[PluginHolder]]](Map.empty)

  /**
   * Scala API: Returns the [[akka.persistence.query.scaladsl.ReadJournal]] specified by the given
   * read journal configuration entry.
   */
  final def readJournalFor[T <: scaladsl.ReadJournal](readJournalPluginId: String): T =
    readJournalPluginFor(readJournalPluginId).scaladslPlugin.asInstanceOf[T]

  /**
   * Java API: Returns the [[akka.persistence.query.javadsl.ReadJournal]] specified by the given
   * read journal configuration entry.
   */
  final def getReadJournalFor[T <: javadsl.ReadJournal](clazz: Class[T], readJournalPluginId: String): T =
    readJournalPluginFor(readJournalPluginId).javadslPlugin.asInstanceOf[T]

  @tailrec private def readJournalPluginFor(readJournalPluginId: String): PluginHolder = {
    val configPath = readJournalPluginId
    val extensionIdMap = readJournalPluginExtensionIds.get
    extensionIdMap.get(configPath) match {
      case Some(extensionId) ⇒
        extensionId(system)
      case None ⇒
        val extensionId = new ExtensionId[PluginHolder] {
          override def createExtension(system: ExtendedActorSystem): PluginHolder = {
            val provider = createPlugin(configPath)
            PluginHolder(provider.scaladslReadJournal(), provider.javadslReadJournal())
          }
        }
        readJournalPluginExtensionIds.compareAndSet(extensionIdMap, extensionIdMap.updated(configPath, extensionId))
        readJournalPluginFor(readJournalPluginId) // Recursive invocation.
    }
  }

  private def createPlugin(configPath: String): ReadJournalProvider = {
    require(
      !isEmpty(configPath) && system.settings.config.hasPath(configPath),
      s"'reference.conf' is missing persistence read journal plugin config path: '${configPath}'")
    val pluginConfig = system.settings.config.getConfig(configPath)
    val pluginClassName = pluginConfig.getString("class")
    log.debug(s"Create plugin: ${configPath} ${pluginClassName}")
    val pluginClass = system.dynamicAccess.getClassFor[AnyRef](pluginClassName).get

    def instantiate(args: collection.immutable.Seq[(Class[_], AnyRef)]) =
      system.dynamicAccess.createInstanceFor[ReadJournalProvider](pluginClass, args)

    instantiate((classOf[ExtendedActorSystem], system) :: (classOf[Config], pluginConfig) ::
      (classOf[String], configPath) :: Nil)
      .recoverWith {
        case x: NoSuchMethodException ⇒ instantiate(
          (classOf[ExtendedActorSystem], system) :: (classOf[Config], pluginConfig) :: Nil)
      }
      .recoverWith { case x: NoSuchMethodException ⇒ instantiate((classOf[ExtendedActorSystem], system) :: Nil) }
      .recoverWith { case x: NoSuchMethodException ⇒ instantiate(Nil) }
      .recoverWith {
        case ex: Exception ⇒ Failure.apply(
          new IllegalArgumentException("Unable to create read journal plugin instance for path " +
            s"[$configPath], class [$pluginClassName]!", ex))
      }.get
  }

  /** Check for default or missing identity. */
  private def isEmpty(text: String) = text == null || text.length == 0
}

