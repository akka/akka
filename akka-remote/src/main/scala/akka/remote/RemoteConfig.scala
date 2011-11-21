/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.util.Duration
import akka.config.ConfigurationException
import java.util.concurrent.TimeUnit
import com.typesafe.config.Config

class RemoteClientSettings(config: Config, defaultTimeUnit: TimeUnit) {
  val SECURE_COOKIE: Option[String] = config.getString("akka.remote.secure-cookie") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }

  val RECONNECTION_TIME_WINDOW = Duration(config.getInt("akka.remote.client.reconnection-time-window"), defaultTimeUnit).toMillis
  val READ_TIMEOUT = Duration(config.getInt("akka.remote.client.read-timeout"), defaultTimeUnit)
  val RECONNECT_DELAY = Duration(config.getInt("akka.remote.client.reconnect-delay"), defaultTimeUnit)
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.client.message-frame-size")
}

class RemoteServerSettings(config: Config, defaultTimeUnit: TimeUnit) {
  import scala.collection.JavaConverters._
  val isRemotingEnabled = config.getStringList("akka.enabled-modules").asScala.exists(_ == "cluster") //TODO FIXME Shouldn't this be "remote"?
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.server.message-frame-size")
  val SECURE_COOKIE: Option[String] = config.getString("akka.remote.secure-cookie") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }
  val REQUIRE_COOKIE = {
    val requireCookie = config.getBoolean("akka.remote.server.require-cookie")
    if (isRemotingEnabled && requireCookie && SECURE_COOKIE.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
    requireCookie
  }

  val USE_PASSIVE_CONNECTIONS = config.getBoolean("akka.remote.use-passive-connections")

  val UNTRUSTED_MODE = config.getBoolean("akka.remote.server.untrusted-mode")
  val PORT = config.getInt("akka.remote.server.port")
  val CONNECTION_TIMEOUT = Duration(config.getInt("akka.remote.server.connection-timeout"), defaultTimeUnit)

  val BACKLOG = config.getInt("akka.remote.server.backlog")
}
