/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote

import akka.util.Duration
import akka.config.Config._
import akka.config.ConfigurationException

object RemoteClientSettings {
  val SECURE_COOKIE: Option[String] = config.getString("akka.remote.secure-cookie", "") match {
    case "" => None
    case cookie => Some(cookie)
  }
  val RECONNECTION_TIME_WINDOW = Duration(config.getInt("akka.remote.client.reconnection-time-window", 600), TIME_UNIT).toMillis
  val READ_TIMEOUT       = Duration(config.getInt("akka.remote.client.read-timeout", 1), TIME_UNIT)
  val RECONNECT_DELAY    = Duration(config.getInt("akka.remote.client.reconnect-delay", 5), TIME_UNIT)
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.client.message-frame-size", 1048576)
}

object RemoteServerSettings {
  val isRemotingEnabled = config.getList("akka.enabled-modules").exists(_ == "remote")
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.server.message-frame-size", 1048576)
  val SECURE_COOKIE      = config.getString("akka.remote.secure-cookie")
  val REQUIRE_COOKIE     = {
    val requireCookie = config.getBool("akka.remote.server.require-cookie", false)
    if (isRemotingEnabled && requireCookie && SECURE_COOKIE.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
    requireCookie
  }

  val UNTRUSTED_MODE            = config.getBool("akka.remote.server.untrusted-mode", false)
  val HOSTNAME                  = config.getString("akka.remote.server.hostname", "localhost")
  val PORT                      = config.getInt("akka.remote.server.port", 2552)
  val CONNECTION_TIMEOUT_MILLIS = Duration(config.getInt("akka.remote.server.connection-timeout", 1), TIME_UNIT)
  val COMPRESSION_SCHEME        = config.getString("akka.remote.compression-scheme", "zlib")
  val ZLIB_COMPRESSION_LEVEL    = {
    val level = config.getInt("akka.remote.zlib-compression-level", 6)
    if (level < 1 && level > 9) throw new IllegalArgumentException(
      "zlib compression level has to be within 1-9, with 1 being fastest and 9 being the most compressed")
    level
  }

  val SECURE = {
    /*if (config.getBool("akka.remote.ssl.service",false)) {
      val properties = List(
        ("key-store-type"  , "keyStoreType"),
        ("key-store"       , "keyStore"),
        ("key-store-pass"  , "keyStorePassword"),
        ("trust-store-type", "trustStoreType"),
        ("trust-store"     , "trustStore"),
        ("trust-store-pass", "trustStorePassword")
        ).map(x => ("akka.remote.ssl." + x._1, "javax.net.ssl." + x._2))

      // If property is not set, and we have a value from our akka.conf, use that value
      for {
        p <- properties if System.getProperty(p._2) eq null
        c <- config.getString(p._1)
      } System.setProperty(p._2, c)

      if (config.getBool("akka.remote.ssl.debug", false)) System.setProperty("javax.net.debug","ssl")
      true
    } else */false
  }
}
