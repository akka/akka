/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.util.Duration
import akka.config.Config._
import akka.config.ConfigurationException

object RemoteClientSettings {
  val SECURE_COOKIE: Option[String] = config.getString("akka.cluster.secure-cookie", "") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }

  val RECONNECTION_TIME_WINDOW = Duration(config.getInt("akka.cluster.client.reconnection-time-window", 600), TIME_UNIT).toMillis
  val READ_TIMEOUT = Duration(config.getInt("akka.cluster.client.read-timeout", 3600), TIME_UNIT)
  val RECONNECT_DELAY = Duration(config.getInt("akka.cluster.client.reconnect-delay", 5), TIME_UNIT)
  val REAP_FUTURES_DELAY = Duration(config.getInt("akka.cluster.client.reap-futures-delay", 5), TIME_UNIT)
  val MESSAGE_FRAME_SIZE = config.getInt("akka.cluster.client.message-frame-size", 1048576)
}

object RemoteServerSettings {
  val isRemotingEnabled = config.getList("akka.enabled-modules").exists(_ == "cluster")
  val MESSAGE_FRAME_SIZE = config.getInt("akka.cluster.server.message-frame-size", 1048576)
  val SECURE_COOKIE = config.getString("akka.cluster.secure-cookie")
  val REQUIRE_COOKIE = {
    val requireCookie = config.getBool("akka.cluster.server.require-cookie", false)
    if (isRemotingEnabled && requireCookie && SECURE_COOKIE.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.cluster.server.require-cookie' is turned on but no secure cookie is defined in 'akka.cluster.secure-cookie'.")
    requireCookie
  }

  val UNTRUSTED_MODE = config.getBool("akka.cluster.server.untrusted-mode", false)
  val PORT = config.getInt("akka.cluster.server.port", 2552)
  val CONNECTION_TIMEOUT = Duration(config.getInt("akka.cluster.server.connection-timeout", 100), TIME_UNIT)
  val COMPRESSION_SCHEME = config.getString("akka.cluster.compression-scheme", "")
  val ZLIB_COMPRESSION_LEVEL = {
    val level = config.getInt("akka.cluster.zlib-compression-level", 6)
    if (level < 1 && level > 9) throw new IllegalArgumentException(
      "zlib compression level has to be within 1-9, with 1 being fastest and 9 being the most compressed")
    level
  }

  val BACKLOG = config.getInt("akka.cluster.server.backlog", 4096)

  val EXECUTION_POOL_KEEPALIVE = Duration(config.getInt("akka.cluster.server.execution-pool-keepalive", 60), TIME_UNIT)

  val EXECUTION_POOL_SIZE = {
    val sz = config.getInt("akka.cluster.server.execution-pool-size", 16)
    if (sz < 1) throw new IllegalArgumentException("akka.cluster.server.execution-pool-size is less than 1")
    sz
  }

  val MAX_CHANNEL_MEMORY_SIZE = {
    val sz = config.getInt("akka.cluster.server.max-channel-memory-size", 0)
    if (sz < 0) throw new IllegalArgumentException("akka.cluster.server.max-channel-memory-size is less than 0")
    sz
  }

  val MAX_TOTAL_MEMORY_SIZE = {
    val sz = config.getInt("akka.cluster.server.max-total-memory-size", 0)
    if (sz < 0) throw new IllegalArgumentException("akka.cluster.server.max-total-memory-size is less than 0")
    sz
  }
}
