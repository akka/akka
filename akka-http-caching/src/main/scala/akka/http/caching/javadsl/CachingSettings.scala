/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.http.caching.javadsl

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.http.caching.scaladsl.{ CachingSettingsImpl, LfuCacheSettingsImpl }
import akka.http.javadsl.settings.SettingsCompanion
import scala.concurrent.duration.Duration
import com.typesafe.config.Config
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.caching.CacheJavaMapping.Implicits._

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class CachingSettings private[http] () { self: CachingSettingsImpl ⇒
  def lfuCacheSettings: LfuCacheSettings

  def withLfuCacheSettings(newSettings: LfuCacheSettings): CachingSettings =
    self.copy(lfuCacheSettings = newSettings.asScala)
}

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LfuCacheSettings private[http] () { self: LfuCacheSettingsImpl ⇒
  def getMaxCapacity: Int
  def getInitialCapacity: Int
  def getTimeToLive: Duration
  def getTimeToIdle: Duration

  def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings
  def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings
  def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings
  def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings
}

object CachingSettings extends SettingsCompanion[CachingSettings] {
  override def create(config: Config): CachingSettings =
    akka.http.caching.scaladsl.CachingSettings(config)
  override def create(configOverrides: String): CachingSettings =
    akka.http.caching.scaladsl.CachingSettings(configOverrides)
  override def create(system: ActorSystem): CachingSettings = create(system.settings.config)
}
