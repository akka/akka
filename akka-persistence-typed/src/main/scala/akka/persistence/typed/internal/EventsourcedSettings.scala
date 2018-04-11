/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.Persistence
import com.typesafe.config.Config

import scala.concurrent.duration._

/** INTERNAL API */
@InternalApi
private[akka] trait EventsourcedSettings {

  def stashCapacity: Int
  def logOnStashing: Boolean
  def stashOverflowStrategyConfigurator: String

  def recoveryEventTimeout: FiniteDuration

  def journalPluginId: String
  def withJournalPluginId(id: String): EventsourcedSettings
  def snapshotPluginId: String
  def withSnapshotPluginId(id: String): EventsourcedSettings
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object EventsourcedSettings {

  def apply(system: ActorSystem[_]): EventsourcedSettings =
    apply(system.settings.config)

  def apply(config: Config): EventsourcedSettings = {
    val typedConfig = config.getConfig("akka.persistence.typed")

    // StashOverflowStrategy
    val stashOverflowStrategyConfigurator = typedConfig.getString("internal-stash-overflow-strategy")

    val stashCapacity = typedConfig.getInt("stash-capacity")
    require(stashCapacity > 0, "stash-capacity MUST be > 0, unbounded buffering is not supported.")

    val logOnStashing = typedConfig.getBoolean("log-stashing")

    EventsourcedSettingsImpl(
      config,
      stashCapacity = stashCapacity,
      stashOverflowStrategyConfigurator,
      logOnStashing = logOnStashing,
      journalPluginId = "",
      snapshotPluginId = ""
    )
  }

  /**
   * INTERNAL API
   */
  private[akka] final def journalConfigFor(config: Config, journalPluginId: String): Config = {
    val defaultJournalPluginId = config.getString("akka.persistence.journal.plugin")
    val configPath = if (journalPluginId == "") defaultJournalPluginId else journalPluginId
    config.getConfig(configPath)
      .withFallback(config.getConfig(Persistence.JournalFallbackConfigPath))
  }

}

@InternalApi
private[persistence] final case class EventsourcedSettingsImpl(
  private val config:                Config,
  stashCapacity:                     Int,
  stashOverflowStrategyConfigurator: String,
  logOnStashing:                     Boolean,
  journalPluginId:                   String,
  snapshotPluginId:                  String
) extends EventsourcedSettings {

  def withJournalPluginId(id: String): EventsourcedSettings = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = id)
  }
  def withSnapshotPluginId(id: String): EventsourcedSettings = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(snapshotPluginId = id)
  }

  private val journalConfig = EventsourcedSettings.journalConfigFor(config, journalPluginId)
  val recoveryEventTimeout = journalConfig.getDuration("recovery-event-timeout", TimeUnit.MILLISECONDS).millis

}

