/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.http.caching.scaladsl

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.http.caching.javadsl
import akka.http.impl.util.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration
import akka.http.impl.util._

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class CachingSettings private[http] () extends javadsl.CachingSettings { self: CachingSettingsImpl ⇒
  override def lfuCacheSettings: LfuCacheSettings

  // overloads for idiomatic Scala use
  def withLfuCacheSettings(newSettings: LfuCacheSettings): CachingSettings =
    self.copy(lfuCacheSettings = newSettings)
}

/** INTERNAL API */
@InternalApi
private[http] final case class CachingSettingsImpl(lfuCacheSettings: LfuCacheSettings) extends CachingSettings {
  override def productPrefix = "CachingSettings"
}

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LfuCacheSettings private[http] () extends javadsl.LfuCacheSettings { self: LfuCacheSettingsImpl ⇒
  def maxCapacity: Int
  def initialCapacity: Int
  def timeToLive: Duration
  def timeToIdle: Duration

  final def getMaxCapacity: Int = maxCapacity
  final def getInitialCapacity: Int = initialCapacity
  final def getTimeToLive: Duration = timeToLive
  final def getTimeToIdle: Duration = timeToIdle

  override def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  override def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings = self.copy(initialCapacity = newInitialCapacity)
  override def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings = self.copy(timeToLive = newTimeToLive)
  override def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings = self.copy(timeToIdle = newTimeToIdle)
}

/** INTERNAL API */
@InternalApi
private[http] final case class LfuCacheSettingsImpl(
  maxCapacity:     Int,
  initialCapacity: Int,
  timeToLive:      Duration,
  timeToIdle:      Duration
) extends LfuCacheSettings {
  override def productPrefix = "LfuCacheSettings"
}

object CachingSettings extends SettingsCompanion[CachingSettings]("akka.http.caching") {
  def fromSubConfig(root: Config, c: Config) = {
    val lfuConfig = c.getConfig("lfu-cache")
    CachingSettingsImpl(
      LfuCacheSettingsImpl(
        lfuConfig getInt "max-capacity",
        lfuConfig getInt "initial-capacity",
        lfuConfig getPotentiallyInfiniteDuration "time-to-live",
        lfuConfig getPotentiallyInfiniteDuration "time-to-idle"
      )
    )
  }
}
