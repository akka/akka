/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit._
import java.net.InetAddress
import akka.ConfigurationException

private[akka] class NettySettings(config: Config, val systemName: String) {

  import config._

  val BackoffTimeout: Duration = Duration(getMilliseconds("backoff-timeout"), MILLISECONDS)

  val SecureCookie: Option[String] = getString("secure-cookie") match {
    case ""     ⇒ None
    case cookie ⇒ Some(cookie)
  }
  val RequireCookie: Boolean = {
    val requireCookie = getBoolean("require-cookie")
    if (requireCookie && SecureCookie.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.netty.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.netty.secure-cookie'.")
    requireCookie
  }

  val UsePassiveConnections: Boolean = getBoolean("use-passive-connections")
  val UseDispatcherForIO: Option[String] = getString("use-dispatcher-for-io") match {
    case "" | null  ⇒ None
    case dispatcher ⇒ Some(dispatcher)
  }

  val ReconnectionTimeWindow: Duration = Duration(getMilliseconds("reconnection-time-window"), MILLISECONDS)
  val ReadTimeout: Duration = Duration(getMilliseconds("read-timeout"), MILLISECONDS)
  val WriteTimeout: Duration = Duration(getMilliseconds("write-timeout"), MILLISECONDS)
  val AllTimeout: Duration = Duration(getMilliseconds("all-timeout"), MILLISECONDS)
  val ReconnectDelay: Duration = Duration(getMilliseconds("reconnect-delay"), MILLISECONDS)
  val MessageFrameSize: Int = getBytes("message-frame-size").toInt

  val Hostname: String = getString("hostname") match {
    case ""    ⇒ InetAddress.getLocalHost.getHostAddress
    case value ⇒ value
  }

  val OutboundLocalAddress: Option[String] = getString("outbound-local-address") match {
    case "auto" | "" | null ⇒ None
    case some               ⇒ Some(some)
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector: Int = getInt("port")

  val ConnectionTimeout: Duration = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)

  val Backlog: Int = getInt("backlog")

  val ExecutionPoolKeepalive: Duration = Duration(getMilliseconds("execution-pool-keepalive"), MILLISECONDS)

  val ExecutionPoolSize: Int = getInt("execution-pool-size") match {
    case sz if sz < 1 ⇒ throw new IllegalArgumentException("akka.remote.netty.execution-pool-size is less than 1")
    case sz           ⇒ sz
  }

  val MaxChannelMemorySize: Long = getBytes("max-channel-memory-size") match {
    case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.netty.max-channel-memory-size is less than 0 bytes")
    case sz           ⇒ sz
  }

  val MaxTotalMemorySize: Long = getBytes("max-total-memory-size") match {
    case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.netty.max-total-memory-size is less than 0 bytes")
    case sz           ⇒ sz
  }

}
