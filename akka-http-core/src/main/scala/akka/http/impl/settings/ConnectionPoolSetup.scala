/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.event.LoggingAdapter
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.settings.ConnectionPoolSettings

/** INTERNAL API */
private[akka] final case class ConnectionPoolSetup(
  settings: ConnectionPoolSettings,
  connectionContext: ConnectionContext = ConnectionContext.noEncryption(),
  log: LoggingAdapter)