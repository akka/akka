/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.http.scaladsl.settings.ClientAutoRedirectSettings.HeadersForwardMode
import com.typesafe.config.Config

final case class ClientAutoRedirectSettingsItem(
  allow: Boolean,
  maxLength: Int,
  forwardHeaders: HeadersForwardMode) {

  /* JAVA APIs */

  def getAllow: Boolean = allow
  def getMaxLength: Int = maxLength
  def getForwardHeaders: HeadersForwardMode = forwardHeaders
}

object ClientAutoRedirectSettingsItem {

  def fromSubConfig(root: Config, inner: Config) = {
    ClientAutoRedirectSettingsItem(
      inner.getBoolean("allow"),
      inner.getInt("max-length"),
      HeadersForwardMode(akka.japi.Util.immutableSeq(inner.getStringList("forward-headers"))))
  }
}