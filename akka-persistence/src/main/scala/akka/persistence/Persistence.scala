/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

import akka.actor._
import akka.event.{ Logging, LoggingAdapter }
import akka.persistence.journal.{ EventAdapters, IdentityEventAdapters }
import akka.util.Collections.EmptyImmutableSeq
import akka.util.Helpers.ConfigOps
import com.typesafe.config.{ Config, ConfigFactory }

import scala.annotation.tailrec
import scala.concurrent.duration._
import akka.util.Reflect

import scala.util.control.NonFatal
import akka.annotation.InternalApi

/**
 * Persistence configuration.
 */
final class PersistenceSettings(config: Config) {

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
 * Identification of [[PersistentActor]].
 */
//#persistence-identity
trait PersistenceIdentity {

  /**
   * Id of the persistent entity for which messages should be replayed.
   */
  def persistenceId: String

  /**
   * Configuration id of the journal plugin servicing this persistent actor.
   * When empty, looks in `akka.persistence.journal.plugin` to find configuration entry path.
   * When configured, uses `journalPluginId` as absolute path to the journal configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  def journalPluginId: String = ""

  /**
   * Configuration id of the snapshot plugin servicing this persistent actor.
   * When empty, looks in `akka.persistence.snapshot-store.plugin` to find configuration entry path.
   * When configured, uses `snapshotPluginId` as absolute path to the snapshot store configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  def snapshotPluginId: String = ""
}

//#persistence-identity

trait PersistenceRecovery {
  //#persistence-recovery
  /**
   * Called when the persistent actor is started for the first time.
   * The returned [[Recovery]] object defines how the Actor will recover its persistent state before
   * handling the first incoming message.
   *
   * To skip recovery completely return `Recovery.none`.
   */
  def recovery: Recovery = Recovery()

  //#persistence-recovery
}

trait PersistenceStash extends Stash with StashFactory {
  /**
   * The returned [[StashOverflowStrategy]] object determines how to handle the message failed to stash
   * when the internal Stash capacity exceeded.
   */
  def internalStashOverflowStrategy: StashOverflowStrategy =
    Persistence(context.system).defaultInternalStashOverflowStrategy
}

trait RuntimePluginConfig {
  /**
   * Additional configuration of the journal plugin servicing this persistent actor.
   * When empty, the whole configuration of the journal plugin will be taken from the [[Config]] loaded into the
   * [[ActorSystem]].
   * When configured, the journal plugin configuration will be taken from this [[Config]] merged with the [[Config]]
   * loaded into the [[ActorSystem]].
   *
   * @return an additional configuration used to configure the journal plugin.
   */
  def journalPluginConfig: Config

  /**
   * Additional configuration of the snapshot plugin servicing this persistent actor.
   * When empty, the whole configuration of the snapshot plugin will be taken from the [[Config]] loaded into the
   * [[ActorSystem]].
   * When configured, the snapshot plugin configuration will be taken from this [[Config]] merged with the [[Config]]
   * loaded into the [[ActorSystem]].
   *
   * @return an additional configuration used to configure the snapshot plugin.
   */
  def snapshotPluginConfig: Config
}

/**
 * Persistence extension provider.
 */
object Persistence extends ExtensionId[Persistence] with ExtensionIdProvider {
  /** Java API. */
  override def get(system: ActorSystem): Persistence = super.get(system)

  def createExtension(system: ExtendedActorSystem): Persistence = new Persistence(system)

  def lookup() = Persistence

  /** INTERNAL API. */
  private[persistence] case class PluginHolder(actor: ActorRef, adapters: EventAdapters, config: Config)
    extends Extension

  /** Config path to fall-back to if a setting is not defined in a specific plugin's config section */
  val JournalFallbackConfigPath = "akka.persistence.journal-plugin-fallback"

  /** Config path to fall-back to if a setting is not defined in a specific snapshot plugin's config section */
  val SnapshotStoreFallbackConfigPath = "akka.persistence.snapshot-store-plugin-fallback"
}

/**
 * Persistence extension.
 */
class Persistence(val system: ExtendedActorSystem) extends Extension {

  import Persistence._

  private def log: LoggingAdapter = Logging(system, getClass.getName)

  private val NoSnapshotStorePluginId = "akka.persistence.no-snapshot-store"

  private val config = system.settings.config.getConfig("akka.persistence")

  /**
   * INTERNAL API: When starting many persistent actors at the same time the journal
   * its data store is protected from being overloaded by limiting number
   * of recoveries that can be in progress at the same time.
   */
  @InternalApi private[akka] val recoveryPermitter: ActorRef = {
    val maxPermits = config.getInt("max-concurrent-recoveries")
    system.systemActorOf(RecoveryPermitter.props(maxPermits), "recoveryPermitter")
  }

  // Lazy, so user is not forced to configure defaults when she is not using them.
  private lazy val defaultJournalPluginId = {
    val configPath = config.getString("journal.plugin")
    require(!isEmpty(configPath), "default journal plugin is not configured, see 'reference.conf'")
    configPath
  }

  // Lazy, so user is not forced to configure defaults when she is not using them.
  private lazy val defaultSnapshotPluginId = {
    val configPath = config.getString("snapshot-store.plugin")

    if (isEmpty(configPath)) {
      log.warning("No default snapshot store configured! " +
        "To configure a default snapshot-store plugin set the `akka.persistence.snapshot-store.plugin` key. " +
        "For details see 'reference.conf'")
      NoSnapshotStorePluginId
    } else configPath
  }

  // Lazy, so user is not forced to configure defaults when she is not using them.
  lazy val defaultInternalStashOverflowStrategy: StashOverflowStrategy =
    system.dynamicAccess.createInstanceFor[StashOverflowStrategyConfigurator](config.getString(
      "internal-stash-overflow-strategy"), EmptyImmutableSeq)
      .map(_.create(system.settings.config)).get

  val settings = new PersistenceSettings(config)

  /** Check for default or missing identity. */
  private def isEmpty(text: String) = text == null || text.length == 0

  /** Discovered persistence journal and snapshot store plugins. */
  private val pluginExtensionId = new AtomicReference[Map[String, ExtensionId[PluginHolder]]](Map.empty)

  config.getStringList("journal.auto-start-journals").forEach(new Consumer[String] {
    override def accept(id: String): Unit = {
      log.info(s"Auto-starting journal plugin `$id`")
      journalFor(id)
    }
  })
  config.getStringList("snapshot-store.auto-start-snapshot-stores").forEach(new Consumer[String] {
    override def accept(id: String): Unit = {
      log.info(s"Auto-starting snapshot store `$id`")
      snapshotStoreFor(id)
    }
  })

  /**
   * Returns an [[akka.persistence.journal.EventAdapters]] object which serves as a per-journal collection of bound event adapters.
   * If no adapters are registered for a given journal the EventAdapters object will simply return the identity
   * adapter for each class, otherwise the most specific adapter matching a given class will be returned.
   */
  final def adaptersFor(journalPluginId: String): EventAdapters = {
    adaptersFor(journalPluginId: String, ConfigFactory.empty)
  }

  /**
   * Returns an [[akka.persistence.journal.EventAdapters]] object which serves as a per-journal collection of bound event adapters.
   * If no adapters are registered for a given journal the EventAdapters object will simply return the identity
   * adapter for each class, otherwise the most specific adapter matching a given class will be returned.
   *
   * The provided journalPluginConfig will be used to configure the plugin instead of the actor system config.
   */
  final def adaptersFor(journalPluginId: String, journalPluginConfig: Config): EventAdapters = {
    val configPath = if (isEmpty(journalPluginId)) defaultJournalPluginId else journalPluginId
    pluginHolderFor(configPath, JournalFallbackConfigPath, journalPluginConfig).adapters
  }

  /**
   * INTERNAL API
   * Looks up [[akka.persistence.journal.EventAdapters]] by journal plugin's ActorRef.
   */
  private[akka] final def adaptersFor(journalPluginActor: ActorRef): EventAdapters = {
    pluginExtensionId.get().values collectFirst {
      case ext if ext(system).actor == journalPluginActor ⇒ ext(system).adapters
    } match {
      case Some(adapters) ⇒ adapters
      case _              ⇒ IdentityEventAdapters
    }
  }

  /**
   * INTERNAL API
   * Returns the plugin config identified by `pluginId`.
   * When empty, looks in `akka.persistence.journal.plugin` to find configuration entry path.
   * When configured, uses `journalPluginId` as absolute path to the journal configuration entry.
   */
  private[akka] final def journalConfigFor(journalPluginId: String, journalPluginConfig: Config = ConfigFactory.empty): Config = {
    val configPath = if (isEmpty(journalPluginId)) defaultJournalPluginId else journalPluginId
    pluginHolderFor(configPath, JournalFallbackConfigPath, journalPluginConfig).config
  }

  /**
   * INTERNAL API
   * Looks up the plugin config by plugin's ActorRef.
   */
  private[akka] final def configFor(journalPluginActor: ActorRef): Config =
    pluginExtensionId.get().values.collectFirst {
      case ext if ext(system).actor == journalPluginActor ⇒ ext(system).config
    } match {
      case Some(conf) ⇒ conf
      case None       ⇒ throw new IllegalArgumentException(s"Unknown plugin actor $journalPluginActor")
    }

  /**
   * INTERNAL API
   * Returns a journal plugin actor identified by `journalPluginId`.
   * When empty, looks in `akka.persistence.journal.plugin` to find configuration entry path.
   * When configured, uses `journalPluginId` as absolute path to the journal configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  private[akka] final def journalFor(journalPluginId: String, journalPluginConfig: Config = ConfigFactory.empty): ActorRef = {
    val configPath = if (isEmpty(journalPluginId)) defaultJournalPluginId else journalPluginId
    pluginHolderFor(configPath, JournalFallbackConfigPath, journalPluginConfig).actor
  }

  /**
   * INTERNAL API
   *
   * Returns a snapshot store plugin actor identified by `snapshotPluginId`.
   * When empty, looks in `akka.persistence.snapshot-store.plugin` to find configuration entry path.
   * When configured, uses `snapshotPluginId` as absolute path to the snapshot store configuration entry.
   * Configuration entry must contain few required fields, such as `class`. See `src/main/resources/reference.conf`.
   */
  private[akka] final def snapshotStoreFor(snapshotPluginId: String, snapshotPluginConfig: Config = ConfigFactory.empty): ActorRef = {
    val configPath = if (isEmpty(snapshotPluginId)) defaultSnapshotPluginId else snapshotPluginId
    pluginHolderFor(configPath, SnapshotStoreFallbackConfigPath, snapshotPluginConfig).actor
  }

  @tailrec private def pluginHolderFor(configPath: String, fallbackPath: String, additionalConfig: Config): PluginHolder = {
    val extensionIdMap = pluginExtensionId.get
    extensionIdMap.get(configPath) match {
      case Some(extensionId) ⇒
        extensionId(system)
      case None ⇒
        val extensionId = new PluginHolderExtensionId(configPath, fallbackPath, additionalConfig)
        pluginExtensionId.compareAndSet(extensionIdMap, extensionIdMap.updated(configPath, extensionId))
        pluginHolderFor(configPath, fallbackPath, additionalConfig) // Recursive invocation.
    }
  }

  private def createPlugin(configPath: String, pluginConfig: Config): ActorRef = {
    val pluginActorName = configPath
    val pluginClassName = pluginConfig.getString("class") match {
      case "" ⇒ throw new IllegalArgumentException("Plugin class name must be defined in config property " +
        s"[$configPath.class]")
      case className ⇒ className
    }
    log.debug(s"Create plugin: $pluginActorName $pluginClassName")
    val pluginClass = system.dynamicAccess.getClassFor[Any](pluginClassName).get
    val pluginDispatcherId = pluginConfig.getString("plugin-dispatcher")
    val pluginActorArgs = try {
      Reflect.findConstructor(pluginClass, List(pluginConfig, configPath)) // will throw if not found
      List(pluginConfig, configPath)
    } catch {
      case NonFatal(_) ⇒
        try {
          Reflect.findConstructor(pluginClass, List(pluginConfig)) // will throw if not found
          List(pluginConfig)
        } catch {
          case NonFatal(_) ⇒ Nil
        } // otherwise use empty constructor
    }
    val pluginActorProps = Props(Deploy(dispatcher = pluginDispatcherId), pluginClass, pluginActorArgs)
    system.systemActorOf(pluginActorProps, pluginActorName)
  }

  private def createAdapters(configPath: String, additionalConfig: Config): EventAdapters = {
    val pluginConfig = additionalConfig.withFallback(system.settings.config).getConfig(configPath)
    EventAdapters(system, pluginConfig)
  }

  /** Creates a canonical persistent actor id from a persistent actor ref. */
  def persistenceId(persistentActor: ActorRef): String = id(persistentActor)

  private def id(ref: ActorRef) = ref.path.toStringWithoutAddress

  private class PluginHolderExtensionId(configPath: String, fallbackPath: String, additionalConfig: Config) extends ExtensionId[PluginHolder] {
    def this(configPath: String, fallbackPath: String) = this(configPath, fallbackPath, ConfigFactory.empty)

    override def createExtension(system: ExtendedActorSystem): PluginHolder = {
      val mergedConfig = additionalConfig.withFallback(system.settings.config)
      require(
        !isEmpty(configPath) && mergedConfig.hasPath(configPath),
        s"'reference.conf' is missing persistence plugin config path: '$configPath'")
      val config: Config = mergedConfig.getConfig(configPath)
        .withFallback(mergedConfig.getConfig(fallbackPath))
      val plugin: ActorRef = createPlugin(configPath, config)
      val adapters: EventAdapters = createAdapters(configPath, mergedConfig)

      PluginHolder(plugin, adapters, config)
    }
  }

}
