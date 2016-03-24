/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.http.impl.settings.ClientAutoRedirectSettingsImpl
import com.typesafe.config.Config

abstract class ClientAutoRedirectSettings private[akka] () { self: ClientAutoRedirectSettingsImpl ⇒
  def getSameOrigin: ClientAutoRedirectSettingsItem
  def getCrossOrigin: ClientAutoRedirectSettingsItem

  def withSameOrigin(newValue: ClientAutoRedirectSettingsItem): ClientAutoRedirectSettings = self.copy(sameOrigin = newValue)
  def withCrossOrigin(newValue: ClientAutoRedirectSettingsItem): ClientAutoRedirectSettings = self.copy(crossOrigin = newValue)
}

object ClientAutoRedirectSettings extends SettingsCompanion[ClientAutoRedirectSettings] {
  trait HeadersForwardMode

  override def create(config: Config): ClientAutoRedirectSettings = ClientAutoRedirectSettingsImpl(config)
  override def create(configOverrides: String): ClientAutoRedirectSettings = ClientAutoRedirectSettingsImpl(configOverrides)
}

