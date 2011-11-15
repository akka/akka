/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.util.Duration
import akka.config.{ Configuration, ConfigurationException }
import java.util.concurrent.TimeUnit

class RemoteClientSettings(config: Configuration, defaultTimeUnit: TimeUnit) {
  val SECURE_COOKIE: Option[String] = config.getString("akka.remote.secure-cookie", "") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }

  val RECONNECTION_TIME_WINDOW = Duration(config.getInt("akka.remote.client.reconnection-time-window", 600), defaultTimeUnit).toMillis
  val READ_TIMEOUT = Duration(config.getInt("akka.remote.client.read-timeout", 3600), defaultTimeUnit)
  val RECONNECT_DELAY = Duration(config.getInt("akka.remote.client.reconnect-delay", 5), defaultTimeUnit)
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.client.message-frame-size", 1048576)
}

class RemoteServerSettings(config: Configuration, defaultTimeUnit: TimeUnit) {
  val isRemotingEnabled = config.getList("akka.enabled-modules").exists(_ == "cluster") //TODO FIXME Shouldn't this be "remote"?
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.server.message-frame-size", 1048576)
  val SECURE_COOKIE = config.getString("akka.remote.secure-cookie")
  val REQUIRE_COOKIE = {
    val requireCookie = config.getBool("akka.remote.server.require-cookie", false)
    if (isRemotingEnabled && requireCookie && SECURE_COOKIE.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
    requireCookie
  }

  val USE_PASSIVE_CONNECTIONS = config.getBool("akka.remote.use-passive-connections", false)

  val UNTRUSTED_MODE = config.getBool("akka.remote.server.untrusted-mode", false)
  val PORT = config.getInt("akka.remote.server.port", 2552)
  val CONNECTION_TIMEOUT = Duration(config.getInt("akka.remote.server.connection-timeout", 100), defaultTimeUnit)

  val BACKLOG = config.getInt("akka.remote.server.backlog", 4096)
}
