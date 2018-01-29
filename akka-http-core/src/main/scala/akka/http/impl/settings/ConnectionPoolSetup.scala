/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.settings.ConnectionPoolSettings

/** INTERNAL API */
@InternalApi
private[akka] final case class ConnectionPoolSetup(
  settings:          ConnectionPoolSettings,
  connectionContext: ConnectionContext      = ConnectionContext.noEncryption(),
  log:               LoggingAdapter)
