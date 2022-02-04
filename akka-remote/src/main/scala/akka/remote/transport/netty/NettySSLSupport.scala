/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport.netty

import scala.annotation.nowarn
import com.typesafe.config.Config
import org.jboss.netty.handler.ssl.SslHandler

import akka.japi.Util._
import akka.util.ccompat._

/**
 * INTERNAL API
 */
private[akka] class SSLSettings(config: Config) {

  import config.getBoolean
  import config.getString
  import config.getStringList

  val SSLKeyStore = getString("key-store")
  val SSLTrustStore = getString("trust-store")
  val SSLKeyStorePassword = getString("key-store-password")
  val SSLKeyPassword = getString("key-password")

  val SSLTrustStorePassword = getString("trust-store-password")

  val SSLEnabledAlgorithms = immutableSeq(getStringList("enabled-algorithms")).to(Set)

  val SSLProtocol = getString("protocol")

  val SSLRandomNumberGenerator = getString("random-number-generator")

  val SSLRequireMutualAuthentication = getBoolean("require-mutual-authentication")

}

/**
 * INTERNAL API
 *
 * Used for adding SSL support to Netty pipeline.
 * The `SSLEngine` is created via the configured [[SSLEngineProvider]].
 */
@ccompatUsedUntil213
@nowarn("msg=deprecated")
private[akka] object NettySSLSupport {

  /**
   * Construct a SSLHandler which can be inserted into a Netty server/client pipeline
   */
  def apply(sslEngineProvider: SSLEngineProvider, isClient: Boolean): SslHandler = {
    val sslEngine =
      if (isClient) sslEngineProvider.createClientSSLEngine()
      else sslEngineProvider.createServerSSLEngine()
    new SslHandler(sslEngine)
  }
}
