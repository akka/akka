/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.http.impl.settings.ClientAutoRedirectSettingsItemImpl
import akka.http.scaladsl.settings.ClientAutoRedirectSettings.HeadersForwardMode

abstract class ClientAutoRedirectSettingsItem private[akka] () extends akka.http.javadsl.settings.ClientAutoRedirectSettingsItem { self: ClientAutoRedirectSettingsItemImpl ⇒

  def allow: Boolean
  def maxLength: Int
  def forwardHeaders: HeadersForwardMode

  /* JAVA APIs */

  final override def getAllow: Boolean = allow
  final override def getMaxLength: Int = maxLength
  final override def getForwardHeaders: HeadersForwardMode = forwardHeaders
}
