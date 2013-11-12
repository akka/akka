/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.dispatch.Dispatchers
import akka.persistence.journal.AsyncWriteJournal

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
  private val snapshotStore = createPlugin("snapshot-store", _ ⇒ DefaultPluginDispatcherId)
  private val journal = createPlugin("journal", clazz ⇒
    if (classOf[AsyncWriteJournal].isAssignableFrom(clazz)) Dispatchers.DefaultDispatcherId else DefaultPluginDispatcherId)

  private[persistence] val publishPluginCommands: Boolean = {
    val path = "publish-plugin-commands"
    // this config option is only used internally (for testing
    // purposes) and is therefore not defined in reference.conf
    config.hasPath(path) && config.getBoolean(path)
  }

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

  private def createPlugin(pluginType: String, dispatcherSelector: Class[_] ⇒ String) = {
    val pluginConfigPath = config.getString(s"${pluginType}.plugin")
    val pluginConfig = system.settings.config.getConfig(pluginConfigPath)
    val pluginClassName = pluginConfig.getString("class")
    val pluginClass = system.dynamicAccess.getClassFor[AnyRef](pluginClassName).get
    val pluginDispatcherId = if (pluginConfig.hasPath("plugin-dispatcher")) pluginConfig.getString("plugin-dispatcher") else dispatcherSelector(pluginClass)
    system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(pluginClass).withDispatcher(pluginDispatcherId), pluginType)
  }

  private def id(ref: ActorRef) = ref.path.toStringWithAddress(system.provider.getDefaultAddress)
}
