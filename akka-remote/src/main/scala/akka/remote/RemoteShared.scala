/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.util.Duration
import akka.config.Config._
import akka.config.ConfigurationException

object RemoteClientSettings {
  val SECURE_COOKIE: Option[String] = config.getString("akka.remote.secure-cookie", "") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }
  val RECONNECTION_TIME_WINDOW = Duration(config.getInt("akka.remote.client.reconnection-time-window", 600), TIME_UNIT).toMillis
  val READ_TIMEOUT = Duration(config.getInt("akka.remote.client.read-timeout", 10), TIME_UNIT)
  val RECONNECT_DELAY = Duration(config.getInt("akka.remote.client.reconnect-delay", 5), TIME_UNIT)
  val REAP_FUTURES_DELAY = Duration(config.getInt("akka.remote.client.reap-futures-delay", 5), TIME_UNIT)
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.client.message-frame-size", 1048576)
  val CONNECTION_TIMEOUT = Duration(config.getInt("akka.remote.client.connection-timeout", 10), TIME_UNIT)
}

object RemoteServerSettings {
  val isRemotingEnabled = config.getList("akka.enabled-modules").exists(_ == "remote")
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.server.message-frame-size", 1048576)
  val SECURE_COOKIE = config.getString("akka.remote.secure-cookie")
  val REQUIRE_COOKIE = {
    val requireCookie = config.getBool("akka.remote.server.require-cookie", false)
    if (isRemotingEnabled && requireCookie && SECURE_COOKIE.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
    requireCookie
  }

  val UNTRUSTED_MODE = config.getBool("akka.remote.server.untrusted-mode", false)
  val HOSTNAME = config.getString("akka.remote.server.hostname", "localhost")
  val PORT = config.getInt("akka.remote.server.port", 2552)

  val BACKLOG = config.getInt("akka.remote.server.backlog", 4096)

  val EXECUTION_POOL_KEEPALIVE = Duration(config.getInt("akka.remote.server.execution-pool-keepalive", 60), TIME_UNIT)

  val EXECUTION_POOL_SIZE = {
    val sz = config.getInt("akka.remote.server.execution-pool-size", 16)
    if (sz < 1) throw new IllegalArgumentException("akka.remote.server.execution-pool-size is less than 1")
    sz
  }

  val MAX_CHANNEL_MEMORY_SIZE = {
    val sz = config.getInt("akka.remote.server.max-channel-memory-size", 0)
    if (sz < 0) throw new IllegalArgumentException("akka.remote.server.max-channel-memory-size is less than 0")
    sz
  }

  val MAX_TOTAL_MEMORY_SIZE = {
    val sz = config.getInt("akka.remote.server.max-total-memory-size", 0)
    if (sz < 0) throw new IllegalArgumentException("akka.remote.server.max-total-memory-size is less than 0")
    sz
  }
}
