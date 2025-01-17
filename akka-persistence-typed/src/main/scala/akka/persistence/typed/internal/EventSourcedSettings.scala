/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import com.typesafe.config.Config

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object EventSourcedSettings {

  def apply(system: ActorSystem[_], journalPluginId: String, snapshotPluginId: String): EventSourcedSettings =
    apply(system.settings.config, journalPluginId, snapshotPluginId, None, None, None)

  def apply(
      system: ActorSystem[_],
      journalPluginId: String,
      snapshotPluginId: String,
      customStashCapacity: Option[Int]): EventSourcedSettings =
    apply(system.settings.config, journalPluginId, snapshotPluginId, customStashCapacity, None, None)

  def apply(
      system: ActorSystem[_],
      journalPluginId: String,
      snapshotPluginId: String,
      customStashCapacity: Option[Int],
      journalPluginConfig: Option[Config],
      snapshotPluginConfig: Option[Config]): EventSourcedSettings =
    apply(
      system.settings.config,
      journalPluginId,
      snapshotPluginId,
      customStashCapacity,
      journalPluginConfig,
      snapshotPluginConfig)

  def apply(
      config: Config,
      journalPluginId: String,
      snapshotPluginId: String,
      customStashCapacity: Option[Int],
      journalPluginConfig: Option[Config],
      snapshotPluginConfig: Option[Config]): EventSourcedSettings = {
    val typedConfig = config.getConfig("akka.persistence.typed")

    val stashOverflowStrategy = typedConfig.getString("stash-overflow-strategy").toLowerCase match {
      case "drop" => StashOverflowStrategy.Drop
      case "fail" => StashOverflowStrategy.Fail
      case unknown =>
        throw new IllegalArgumentException(s"Unknown value for stash-overflow-strategy: [$unknown]")
    }

    val stashCapacity = customStashCapacity.getOrElse(typedConfig.getInt("stash-capacity"))
    require(stashCapacity > 0, "stash-capacity MUST be > 0, unbounded buffering is not supported.")

    val logOnStashing = typedConfig.getBoolean("log-stashing")

    val useContextLoggerForInternalLogging = typedConfig.getBoolean("use-context-logger-for-internal-logging")

    EventSourcedSettings(
      stashCapacity = stashCapacity,
      stashOverflowStrategy,
      logOnStashing = logOnStashing,
      journalPluginId,
      snapshotPluginId,
      journalPluginConfig,
      snapshotPluginConfig,
      useContextLoggerForInternalLogging)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final case class EventSourcedSettings(
    stashCapacity: Int,
    stashOverflowStrategy: StashOverflowStrategy,
    logOnStashing: Boolean,
    journalPluginId: String,
    snapshotPluginId: String,
    journalPluginConfig: Option[Config],
    snapshotPluginConfig: Option[Config],
    useContextLoggerForInternalLogging: Boolean) {

  require(journalPluginId != null, "journal plugin id must not be null; use empty string for 'default' journal")
  require(
    snapshotPluginId != null,
    "snapshot plugin id must not be null; use empty string for 'default' snapshot store")

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] sealed trait StashOverflowStrategy

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object StashOverflowStrategy {
  case object Drop extends StashOverflowStrategy
  case object Fail extends StashOverflowStrategy
}
