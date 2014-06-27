/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._

import com.typesafe.config.Config

import akka.actor._
import akka.dispatch.Dispatchers
import akka.persistence.journal.AsyncWriteJournal
import akka.util.Helpers.ConfigOps

/**
 * Persistence configuration.
 */
final class PersistenceSettings(config: Config) {
  object journal {
    val maxMessageBatchSize: Int =
      config.getInt("journal.max-message-batch-size")

    val maxConfirmationBatchSize: Int =
      config.getInt("journal.max-confirmation-batch-size")

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

    val publishConfirmations: Boolean = {
      val path = "publish-confirmations"
      config.hasPath(path) && config.getBoolean(path)
    }
  }
}

/**
 * Persistence extension.
 */
object Persistence extends ExtensionId[Persistence] with ExtensionIdProvider {
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
  private val DefaultPluginDispatcherId = "akka.persistence.dispatchers.default-plugin-dispatcher"
  private val config = system.settings.config.getConfig("akka.persistence")

  val settings = new PersistenceSettings(config)

  private val snapshotStore = createPlugin("snapshot-store") { _ ⇒
    DefaultPluginDispatcherId
  }

  private val journal = createPlugin("journal") { clazz ⇒
    if (classOf[AsyncWriteJournal].isAssignableFrom(clazz)) Dispatchers.DefaultDispatcherId
    else DefaultPluginDispatcherId
  }

  private val confirmationBatchLayer = system.systemActorOf(
    Props(classOf[DeliveredByChannelBatching], journal, settings), "confirmation-batch-layer")

  private val deletionBatchLayer = system.systemActorOf(
    Props(classOf[DeliveredByPersistentChannelBatching], journal, settings), "deletion-batch-layer")

  /**
   * Creates a canonical processor id from a processor actor ref.
   */
  @deprecated("Use `persistenceId` instead. Processor will be removed.", since = "2.3.4")
  def processorId(processor: ActorRef): String = id(processor)

  /**
   * Creates a canonical persistent actor id from a processor actor ref.
   */
  def persistenceId(persistentActor: ActorRef): String = id(persistentActor)

  /**
   * Creates a canonical channel id from a channel actor ref.
   */
  @deprecated("Channels will be removed. You may want to use `akka.persistence.AtLeastOnceDelivery` instead.", since = "2.3.4")
  def channelId(channel: ActorRef): String = id(channel)

  /**
   * Returns a snapshot store for a processor identified by `persistenceId`.
   */
  def snapshotStoreFor(persistenceId: String): ActorRef = {
    // Currently returns a snapshot store singleton but this methods allows for later
    // optimizations where each processor can have its own snapshot store actor.
    snapshotStore
  }

  /**
   * Returns a journal for a processor identified by `persistenceId`.
   */
  def journalFor(persistenceId: String): ActorRef = {
    // Currently returns a journal singleton but this methods allows for later
    // optimizations where each processor can have its own journal actor.
    journal
  }

  /**
   * INTERNAL API.
   */
  private[persistence] def confirmationBatchingJournalForChannel(channelId: String): ActorRef =
    confirmationBatchLayer

  /**
   * INTERNAL API.
   */
  private[persistence] def deletionBatchingJournalForChannel(channelId: String): ActorRef =
    deletionBatchLayer

  private def createPlugin(pluginType: String)(dispatcherSelector: Class[_] ⇒ String) = {
    val pluginConfigPath = config.getString(s"${pluginType}.plugin")
    val pluginConfig = system.settings.config.getConfig(pluginConfigPath)
    val pluginClassName = pluginConfig.getString("class")
    val pluginClass = system.dynamicAccess.getClassFor[AnyRef](pluginClassName).get
    val pluginDispatcherId = if (pluginConfig.hasPath("plugin-dispatcher")) pluginConfig.getString("plugin-dispatcher") else dispatcherSelector(pluginClass)
    system.systemActorOf(Props(pluginClass).withDispatcher(pluginDispatcherId), pluginType)
  }

  private def id(ref: ActorRef) = ref.path.toStringWithoutAddress
}
