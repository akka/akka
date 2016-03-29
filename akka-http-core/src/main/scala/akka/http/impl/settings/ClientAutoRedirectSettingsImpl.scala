/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.http.impl.util.SettingsCompanion
import com.typesafe.config.Config

/** INTERNAL API */
private[akka] final case class ClientAutoRedirectSettingsImpl(
  sameOrigin: ClientAutoRedirectSettingsItem,
  crossOrigin: ClientAutoRedirectSettingsItem) extends akka.http.scaladsl.settings.ClientAutoRedirectSettings

object ClientAutoRedirectSettingsImpl extends SettingsCompanion[ClientAutoRedirectSettingsImpl]("akka.http.client.redirect") {

  override def fromSubConfig(root: Config, inner: Config): ClientAutoRedirectSettingsImpl = {
    val sameOriginConfig = inner.withFallback(root.getConfig(prefix)).getConfig("same-origin")
    val crossOriginConfig = inner.withFallback(root.getConfig(prefix)).getConfig("cross-origin")
    ClientAutoRedirectSettingsImpl(
      ClientAutoRedirectSettingsItem.fromSubConfig(root, sameOriginConfig),
      ClientAutoRedirectSettingsItem.fromSubConfig(root, crossOriginConfig))
  }
}