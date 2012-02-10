/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit._
import java.net.InetAddress
import akka.config.ConfigurationException

class NettySettings(config: Config, val systemName: String) {

  import config._

  val BackoffTimeout = Duration(getMilliseconds("backoff-timeout"), MILLISECONDS)

  val SecureCookie: Option[String] = getString("secure-cookie") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }
  val RequireCookie = {
    val requireCookie = getBoolean("require-cookie")
    if (requireCookie && SecureCookie.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.netty.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.netty.secure-cookie'.", null)
    requireCookie
  }

  val UsePassiveConnections = getBoolean("use-passive-connections")

  val ReconnectionTimeWindow = Duration(getMilliseconds("reconnection-time-window"), MILLISECONDS)
  val ReadTimeout = Duration(getMilliseconds("read-timeout"), MILLISECONDS)
  val WriteTimeout = Duration(getMilliseconds("write-timeout"), MILLISECONDS)
  val AllTimeout = Duration(getMilliseconds("all-timeout"), MILLISECONDS)
  val ReconnectDelay = Duration(getMilliseconds("reconnect-delay"), MILLISECONDS)
  val MessageFrameSize = getBytes("message-frame-size").toInt

  val Hostname = getString("hostname") match {
    case ""    ⇒ InetAddress.getLocalHost.getHostAddress
    case value ⇒ value
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector = getInt("port")

  val ConnectionTimeout = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)

  val Backlog = getInt("backlog")

  val ExecutionPoolKeepalive = Duration(getMilliseconds("execution-pool-keepalive"), MILLISECONDS)

  val ExecutionPoolSize = getInt("execution-pool-size") match {
    case sz if sz < 1 ⇒ throw new IllegalArgumentException("akka.remote.netty.execution-pool-size is less than 1")
    case sz           ⇒ sz
  }

  val MaxChannelMemorySize = getBytes("max-channel-memory-size") match {
    case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.netty.max-channel-memory-size is less than 0 bytes")
    case sz           ⇒ sz
  }

  val MaxTotalMemorySize = getBytes("max-total-memory-size") match {
    case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.netty.max-total-memory-size is less than 0 bytes")
    case sz           ⇒ sz
  }

}