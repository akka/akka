/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.http.scaladsl.settings.ClientAutoRedirectSettings.HeadersForwardMode
import com.typesafe.config.Config

private[akka] final case class ClientAutoRedirectSettingsItemImpl(
  allow: Boolean,
  maxLength: Int,
  forwardHeaders: HeadersForwardMode) extends akka.http.scaladsl.settings.ClientAutoRedirectSettingsItem

object ClientAutoRedirectSettingsItemImpl {

  def fromSubConfig(root: Config, inner: Config) = {
    ClientAutoRedirectSettingsItemImpl(
      inner.getBoolean("allow"),
      inner.getInt("max-length"),
      HeadersForwardMode(akka.japi.Util.immutableSeq(inner.getStringList("forward-headers"))))
  }
}