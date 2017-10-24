/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.PreviewServerSettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 *
 * Options that are in "preview" or "early access" mode.
 * These options may change and/or be removed within patch releases
 * without early notice (e.g. by moving them into a stable supported place).
 */
@ApiMayChange @DoNotInherit
abstract class PreviewServerSettings private[akka] () extends akka.http.javadsl.settings.PreviewServerSettings {
  self: PreviewServerSettingsImpl ⇒

  override def enableHttp2: Boolean

  // --

  // override for more specific return type
  override def withEnableHttp2(newValue: Boolean): PreviewServerSettings = self.copy(enableHttp2 = newValue)

}

object PreviewServerSettings extends SettingsCompanion[PreviewServerSettings] {
  def fromSubConfig(root: Config, c: Config) =
    PreviewServerSettingsImpl.fromSubConfig(root, c)
  override def apply(config: Config): PreviewServerSettings = PreviewServerSettingsImpl(config)
  override def apply(configOverrides: String): PreviewServerSettings = PreviewServerSettingsImpl(configOverrides)
}
