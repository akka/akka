/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.Persistence
import com.typesafe.config.Config

/**
 * INTERNAL API
 */
@InternalApi private[akka] object EventSourcedSettings {

  def apply(system: ActorSystem[_], journalPluginId: String, snapshotPluginId: String): EventSourcedSettings =
    apply(system.settings.config, journalPluginId, snapshotPluginId)

  def apply(config: Config, journalPluginId: String, snapshotPluginId: String): EventSourcedSettings = {
    val typedConfig = config.getConfig("akka.persistence.typed")

    val stashOverflowStrategy = typedConfig.getString("stash-overflow-strategy").toLowerCase match {
      case "drop" => StashOverflowStrategy.Drop
      case "fail" => StashOverflowStrategy.Fail
      case unknown =>
        throw new IllegalArgumentException(s"Unknown value for stash-overflow-strategy: [$unknown]")
    }

    val stashCapacity = typedConfig.getInt("stash-capacity")
    require(stashCapacity > 0, "stash-capacity MUST be > 0, unbounded buffering is not supported.")

    val logOnStashing = typedConfig.getBoolean("log-stashing")

    val journalConfig = journalConfigFor(config, journalPluginId)
    val recoveryEventTimeout: FiniteDuration =
      journalConfig.getDuration("recovery-event-timeout", TimeUnit.MILLISECONDS).millis

    EventSourcedSettings(
      stashCapacity = stashCapacity,
      stashOverflowStrategy,
      logOnStashing = logOnStashing,
      recoveryEventTimeout,
      journalPluginId,
      snapshotPluginId)
  }

  private[akka] final def journalConfigFor(config: Config, journalPluginId: String): Config = {
    val defaultJournalPluginId = config.getString("akka.persistence.journal.plugin")
    val configPath = if (journalPluginId == "") defaultJournalPluginId else journalPluginId
    config.getConfig(configPath).withFallback(config.getConfig(Persistence.JournalFallbackConfigPath))
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
    recoveryEventTimeout: FiniteDuration,
    journalPluginId: String,
    snapshotPluginId: String) {

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
