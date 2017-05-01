/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import akka.actor.ActorSystem
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
abstract class PreviewServerSettings private[akka] () { self: PreviewServerSettingsImpl ⇒
  /**
   * Configures the Http extension to bind using HTTP/2 if given an
   * [[akka.http.scaladsl.HttpsConnectionContext]]. Otherwise binds as plain HTTP.
   *
   * Please note that when using this mode of binding you MUST include
   * `"com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion` in your
   * dependencies / classpath.
   */
  def enableHttp2: Boolean
}

object PreviewServerSettings extends SettingsCompanion[PreviewServerSettings] {
  override def create(config: Config): PreviewServerSettings = PreviewServerSettingsImpl(config)
  override def create(configOverrides: String): PreviewServerSettings = PreviewServerSettingsImpl(configOverrides)
  override def create(system: ActorSystem): PreviewServerSettings = create(system.settings.config)
}
