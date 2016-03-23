/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.settings

import akka.http.impl.settings.ClientAutoRedirectSettingsItemImpl
import akka.http.javadsl.settings.ClientAutoRedirectSettings.HeadersForwardMode

abstract class ClientAutoRedirectSettingsItem private[akka] () { self: ClientAutoRedirectSettingsItemImpl ⇒
  def getAllow: Boolean
  def getMaxLength: Int
  def getForwardHeaders: HeadersForwardMode
}
