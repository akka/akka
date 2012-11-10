/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import com.typesafe.config.Config
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit._
import java.net.InetAddress
import akka.ConfigurationException
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.concurrent.util.FiniteDuration

private[akka] class NettySettings(config: Config, val systemName: String) {

  import config._

  val BackoffTimeout: FiniteDuration = Duration(getMilliseconds("backoff-timeout"), MILLISECONDS)

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

  val ReconnectionTimeWindow: FiniteDuration = Duration(getMilliseconds("reconnection-time-window"), MILLISECONDS)
  val ReadTimeout: FiniteDuration = Duration(getMilliseconds("read-timeout"), MILLISECONDS)
  val WriteTimeout: FiniteDuration = Duration(getMilliseconds("write-timeout"), MILLISECONDS)
  val AllTimeout: FiniteDuration = Duration(getMilliseconds("all-timeout"), MILLISECONDS)
  val ReconnectDelay: FiniteDuration = Duration(getMilliseconds("reconnect-delay"), MILLISECONDS)

  val MessageFrameSize: Int = getBytes("message-frame-size").toInt

  private[this] def optionSize(s: String): Option[Int] = getBytes(s).toInt match {
    case 0 ⇒ None
    case x if x < 0 ⇒
      throw new ConfigurationException("Setting '%s' must be 0 or positive (and fit in an Int)" format s)
    case other ⇒ Some(other)
  }

  val WriteBufferHighWaterMark: Option[Int] = optionSize("write-buffer-high-water-mark")
  val WriteBufferLowWaterMark: Option[Int] = optionSize("write-buffer-low-water-mark")
  val SendBufferSize: Option[Int] = optionSize("send-buffer-size")
  val ReceiveBufferSize: Option[Int] = optionSize("receive-buffer-size")

  val forceBindAddress: Boolean = getBoolean("force-bind-address")

  val Hostname: String = getString("hostname") match {
    case ""    ⇒ InetAddress.getLocalHost.getHostAddress
    case value ⇒ value
  }

  /**
   * Used exclusively by akka.remote.netty.Server to determine if a connection should be accepted.
   */
  val BindHostname: String = if (forceBindAddress) {
    "0.0.0.0" //accept all incoming connections
  } else {
    Hostname // accept only incoming connection addressed to me, IPs matching my NIC card
  }

  val OutboundLocalAddress: Option[String] = getString("outbound-local-address") match {
    case "auto" | "" | null ⇒ None
    case some               ⇒ Some(some)
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector: Int = getInt("port")

  val ConnectionTimeout: FiniteDuration = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)

  val Backlog: Int = getInt("backlog")

  val ExecutionPoolKeepalive: FiniteDuration = Duration(getMilliseconds("execution-pool-keepalive"), MILLISECONDS)

  val ExecutionPoolSize: Int = getInt("execution-pool-size") match {
    case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.netty.execution-pool-size is less than 0")
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

  val SSLKeyStore = getString("ssl.key-store") match {
    case ""       ⇒ None
    case keyStore ⇒ Some(keyStore)
  }

  val SSLTrustStore = getString("ssl.trust-store") match {
    case ""         ⇒ None
    case trustStore ⇒ Some(trustStore)
  }

  val SSLKeyStorePassword = getString("ssl.key-store-password") match {
    case ""       ⇒ None
    case password ⇒ Some(password)
  }

  val SSLTrustStorePassword = getString("ssl.trust-store-password") match {
    case ""       ⇒ None
    case password ⇒ Some(password)
  }

  val SSLEnabledAlgorithms = iterableAsScalaIterableConverter(getStringList("ssl.enabled-algorithms")).asScala.toSet[String]

  val SSLProtocol = getString("ssl.protocol") match {
    case ""       ⇒ None
    case protocol ⇒ Some(protocol)
  }

  val SSLRandomSource = getString("ssl.sha1prng-random-source") match {
    case ""   ⇒ None
    case path ⇒ Some(path)
  }

  val SSLRandomNumberGenerator = getString("ssl.random-number-generator") match {
    case ""  ⇒ None
    case rng ⇒ Some(rng)
  }

  val EnableSSL = {
    val enableSSL = getBoolean("ssl.enable")
    if (enableSSL) {
      if (SSLProtocol.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.netty.ssl.enable is turned on but no protocol is defined in 'akka.remote.netty.ssl.protocol'.")
      if (SSLKeyStore.isEmpty && SSLTrustStore.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.netty.ssl.enable is turned on but no key/trust store is defined in 'akka.remote.netty.ssl.key-store' / 'akka.remote.netty.ssl.trust-store'.")
      if (SSLKeyStore.isDefined && SSLKeyStorePassword.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.netty.ssl.key-store' is defined but no key-store password is defined in 'akka.remote.netty.ssl.key-store-password'.")
      if (SSLTrustStore.isDefined && SSLTrustStorePassword.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.netty.ssl.trust-store' is defined but no trust-store password is defined in 'akka.remote.netty.ssl.trust-store-password'.")
    }
    enableSSL
  }
}
