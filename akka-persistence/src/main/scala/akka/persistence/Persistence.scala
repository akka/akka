/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import com.typesafe.config.Config
import akka.actor._
import akka.dispatch.Dispatchers
import akka.persistence.journal.AsyncWriteJournal
import akka.util.Helpers.ConfigOps
import akka.event.LoggingAdapter
import akka.event.Logging
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * Persistence configuration.
 */
final class PersistenceSettings(config: Config) {
  object journal {
    val maxMessageBatchSize: Int =
      config.getInt("journal.max-message-batch-size")

    val maxDeletionBatchSize: Int =
      config.getInt("journal.max-deletion-batch-size")
  }

  object view {
    val autoUpdate: Boolean =
      config.getBoolean("view.auto-update")

    val autoUpdateInterval: FiniteDuration =
      config.getMillisDuration("view.auto-update-interval")

    val autoUpdateReplayMax: Long =
      posMax(config.getLong("view.auto-update-replay-max"))

    private def posMax(v: Long) =
      if (v < 0) Long.MaxValue else v
  }

  object atLeastOnceDelivery {

    val redeliverInterval: FiniteDuration =
      config.getMillisDuration("at-least-once-delivery.redeliver-interval")

    val redeliveryBurstLimit: Int =
      config.getInt("at-least-once-delivery.redelivery-burst-limit")

    val warnAfterNumberOfUnconfirmedAttempts: Int =
      config.getInt("at-least-once-delivery.warn-after-number-of-unconfirmed-attempts")

    val maxUnconfirmedMessages: Int =
      config.getInt("at-least-once-delivery.max-unconfirmed-messages")
  }

  /**
   * INTERNAL API.
   *
   * These config options are only used internally for testing
   * purposes and are therefore not defined in reference.conf
   */
  private[persistence] object internal {
    val publishPluginCommands: Boolean = {
      val path = "publish-plugin-commands"
      config.hasPath(path) && config.getBoolean(path)
    }

  }
}

/**
 * Identification of [[PersistentActor]] or [[PersistentView]].
 */
//#persistence-identity
trait PersistenceIdentity {

  /**
   * Id of the persistent entity for which messages should be replayed.
   */
  def persistenceId: String

  /**
   * Configuration id of the journal plugin servicing this persistent actor or view.
   * When empty, looks in `akka.persistence.journal.plugin` to find configuration entry path.
   * When configured, uses `journalPluginId` as absolute path to the journal configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  def journalPluginId: String = ""

  /**
   * Configuration id of the snapshot plugin servicing this persistent actor or view.
   * When empty, looks in `akka.persistence.snapshot-store.plugin` to find configuration entry path.
   * When configured, uses `snapshotPluginId` as absolute path to the snapshot store configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  def snapshotPluginId: String = ""

}
//#persistence-identity

/**
 * Persistence extension provider.
 */
object Persistence extends ExtensionId[Persistence] with ExtensionIdProvider {
  /** Java API. */
  override def get(system: ActorSystem): Persistence = super.get(system)
  def createExtension(system: ExtendedActorSystem): Persistence = new Persistence(system)
  def lookup() = Persistence
  /** INTERNAL API. */
  private[persistence] case class PluginHolder(actor: ActorRef) extends Extension
}

/**
 * Persistence extension.
 */
class Persistence(val system: ExtendedActorSystem) extends Extension {
  import Persistence._

  private def log: LoggingAdapter = Logging(system, getClass.getName)

  private val DefaultPluginDispatcherId = "akka.persistence.dispatchers.default-plugin-dispatcher"

  private val config = system.settings.config.getConfig("akka.persistence")

  // Lazy, so user is not forced to configure defaults when she is not using them.
  private lazy val defaultJournalPluginId = {
    val configPath = config.getString("journal.plugin")
    require(!isEmpty(configPath), "default journal plugin is not configured, see 'reference.conf'")
    configPath
  }

  // Lazy, so user is not forced to configure defaults when she is not using them.
  private lazy val defaultSnapshotPluginId = {
    val configPath = config.getString("snapshot-store.plugin")
    require(!isEmpty(configPath), "default snapshot-store plugin is not configured, see 'reference.conf'")
    configPath
  }

  val settings = new PersistenceSettings(config)

  private def journalDispatchSelector(klaz: Class[_]): String =
    if (classOf[AsyncWriteJournal].isAssignableFrom(klaz)) Dispatchers.DefaultDispatcherId else DefaultPluginDispatcherId

  private def snapshotDispatchSelector(klaz: Class[_]): String =
    DefaultPluginDispatcherId

  /** Check for default or missing identity. */
  private def isEmpty(text: String) = text == null || text.length == 0

  /** Discovered persistence journal plugins. */
  private val journalPluginExtensionId = new AtomicReference[Map[String, ExtensionId[PluginHolder]]](Map.empty)

  /** Discovered persistence snapshot store plugins. */
  private val snapshotPluginExtensionId = new AtomicReference[Map[String, ExtensionId[PluginHolder]]](Map.empty)

  /**
   * Returns a journal plugin actor identified by `journalPluginId`.
   * When empty, looks in `akka.persistence.journal.plugin` to find configuration entry path.
   * When configured, uses `journalPluginId` as absolute path to the journal configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  @tailrec final def journalFor(journalPluginId: String): ActorRef = {
    val configPath = if (isEmpty(journalPluginId)) defaultJournalPluginId else journalPluginId
    val extensionIdMap = journalPluginExtensionId.get
    extensionIdMap.get(configPath) match {
      case Some(extensionId) ⇒
        extensionId(system).actor
      case None ⇒
        val extensionId = new ExtensionId[PluginHolder] {
          override def createExtension(system: ExtendedActorSystem): PluginHolder =
            PluginHolder(createPlugin(configPath)(journalDispatchSelector))
        }
        journalPluginExtensionId.compareAndSet(extensionIdMap, extensionIdMap.updated(configPath, extensionId))
        journalFor(journalPluginId) // Recursive invocation.
    }
  }

  /**
   * Returns a snapshot store plugin actor identified by `snapshotPluginId`.
   * When empty, looks in `akka.persistence.snapshot-store.plugin` to find configuration entry path.
   * When configured, uses `snapshotPluginId` as absolute path to the snapshot store configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  @tailrec final def snapshotStoreFor(snapshotPluginId: String): ActorRef = {
    val configPath = if (isEmpty(snapshotPluginId)) defaultSnapshotPluginId else snapshotPluginId
    val extensionIdMap = snapshotPluginExtensionId.get
    extensionIdMap.get(configPath) match {
      case Some(extensionId) ⇒
        extensionId(system).actor
      case None ⇒
        val extensionId = new ExtensionId[PluginHolder] {
          override def createExtension(system: ExtendedActorSystem): PluginHolder =
            PluginHolder(createPlugin(configPath)(snapshotDispatchSelector))
        }
        snapshotPluginExtensionId.compareAndSet(extensionIdMap, extensionIdMap.updated(configPath, extensionId))
        snapshotStoreFor(snapshotPluginId) // Recursive invocation.
    }
  }

  private def createPlugin(configPath: String)(dispatcherSelector: Class[_] ⇒ String) = {
    require(!isEmpty(configPath) && system.settings.config.hasPath(configPath),
      s"'reference.conf' is missing persistence plugin config path: '${configPath}'")
    val pluginActorName = configPath
    val pluginConfig = system.settings.config.getConfig(configPath)
    val pluginClassName = pluginConfig.getString("class")
    log.debug(s"Create plugin: ${pluginActorName} ${pluginClassName}")
    val pluginClass = system.dynamicAccess.getClassFor[AnyRef](pluginClassName).get
    val pluginInjectConfig = if (pluginConfig.hasPath("inject-config")) pluginConfig.getBoolean("inject-config") else false
    val pluginDispatcherId = if (pluginConfig.hasPath("plugin-dispatcher")) pluginConfig.getString("plugin-dispatcher") else dispatcherSelector(pluginClass)
    val pluginActorArgs = if (pluginInjectConfig) List(pluginConfig) else Nil
    val pluginActorProps = Props(Deploy(dispatcher = pluginDispatcherId), pluginClass, pluginActorArgs)
    system.systemActorOf(pluginActorProps, pluginActorName)
  }

  /** Creates a canonical persistent actor id from a persistent actor ref. */
  def persistenceId(persistentActor: ActorRef): String = id(persistentActor)

  private def id(ref: ActorRef) = ref.path.toStringWithoutAddress

}
