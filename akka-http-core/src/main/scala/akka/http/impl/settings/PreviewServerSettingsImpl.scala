/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanion
import com.typesafe.config.Config

@InternalApi
private[http] final case class PreviewServerSettingsImpl(
  enableHttp2: Boolean
) extends akka.http.scaladsl.settings.PreviewServerSettings {

  override def productPrefix: String = "PreviewServerSettings"
}

object PreviewServerSettingsImpl extends SettingsCompanion[PreviewServerSettingsImpl]("akka.http.server.preview") {
  def fromSubConfig(root: Config, c: Config) = PreviewServerSettingsImpl(
    c getBoolean "enable-http2"
  )
}
