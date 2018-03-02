/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.internal

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.Logging.LogLevel
import com.typesafe.config.Config

import scala.concurrent.duration._

trait EventsourcedSettings {

  def stashCapacity: Int
  // def stashOverflowStrategyName: String // TODO not supported, the stash just throws for now
  def stashingLogLevel: LogLevel
  def journalPluginId: String
  def snapshotPluginId: String
  def recoveryEventTimeout: FiniteDuration

  def withJournalPluginId(id: Option[String]): EventsourcedSettings
  def withSnapshotPluginId(id: Option[String]): EventsourcedSettings
}

object EventsourcedSettings {

  def apply(system: ActorSystem[_]): EventsourcedSettings =
    apply(system.settings.config)

  def apply(config: Config): EventsourcedSettings = {
    val typedConfig = config.getConfig("akka.persistence.typed")
    val untypedConfig = config.getConfig("akka.persistence")

    // StashOverflowStrategy
    val internalStashOverflowStrategy =
      untypedConfig.getString("internal-stash-overflow-strategy") // FIXME or copy it to typed?

    val stashCapacity = typedConfig.getInt("stash-capacity")

    val stashingLogLevel = typedConfig.getString("log-stashing") match {
      case "off"         ⇒ Logging.OffLevel
      case "on" | "true" ⇒ Logging.DebugLevel
      case l             ⇒ Logging.levelFor(l).getOrElse(Logging.OffLevel)
    }

    // FIXME this is wrong I think
    val recoveryEventTimeout = 10.seconds // untypedConfig.getDuration("plugin-journal-fallback.recovery-event-timeout", TimeUnit.MILLISECONDS).millis

    EventsourcedSettingsImpl(
      stashCapacity = stashCapacity,
      internalStashOverflowStrategy,
      stashingLogLevel = stashingLogLevel,
      journalPluginId = "",
      snapshotPluginId = "",
      recoveryEventTimeout = recoveryEventTimeout
    )
  }
}

@InternalApi
private[persistence] final case class EventsourcedSettingsImpl(
  stashCapacity:             Int,
  stashOverflowStrategyName: String,
  stashingLogLevel:          LogLevel,
  journalPluginId:           String,
  snapshotPluginId:          String,
  recoveryEventTimeout:      FiniteDuration
) extends EventsourcedSettings {

  def withJournalPluginId(id: Option[String]): EventsourcedSettings = id match {
    case Some(identifier) ⇒ copy(journalPluginId = identifier)
    case _                ⇒ this
  }
  def withSnapshotPluginId(id: Option[String]): EventsourcedSettings = id match {
    case Some(identifier) ⇒ copy(snapshotPluginId = identifier)
    case _                ⇒ this
  }
}

