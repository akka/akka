/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery
package tcp

import java.security.KeyStore
import java.security.SecureRandom

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.setup.Setup
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.remote.artery.tcp.ssl.JksManagerProviders
import akka.remote.artery.tcp.ssl.NoopSessionVerifier
import akka.remote.artery.tcp.ssl.SslFactory
import akka.util.ccompat._
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager

@ccompatUsedUntil213
trait SSLEngineProvider {

  def createServerSSLEngine(hostname: String, port: Int): SSLEngine

  def createClientSSLEngine(hostname: String, port: Int): SSLEngine

  /**
   * Verification that will be called after every successful handshake
   * to verify additional session information. Return `None` if valid
   * otherwise `Some` with explaining cause.
   */
  def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable]

  /**
   * Verification that will be called after every successful handshake
   * to verify additional session information. Return `None` if valid
   * otherwise `Some` with explaining cause.
   */
  def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable]

}

class SslTransportException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * Config in akka.remote.artery.ssl.config-ssl-engine
 *
 * Subclass may override protected methods to replace certain parts, such as key and trust manager.
 */
class ConfigSSLEngineProvider(protected val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      system.settings.config.getConfig("akka.remote.artery.ssl.config-ssl-engine"),
      Logging.withMarker(system, classOf[ConfigSSLEngineProvider].getName))

  // Settings to read keystores (private keys and trusted certificates)
  @deprecated("Implement and setup a SslManagersProvider instead of using this setting.", "2.6.6")
  val SSLKeyStore: String = config.getString("key-store")
  @deprecated("Implement and setup a SslManagersProvider instead of using this setting.", "2.6.6")
  val SSLTrustStore: String = config.getString("trust-store")
  @deprecated("Implement and setup a SslManagersProvider instead of using this setting.", "2.6.6")
  val SSLKeyStorePassword: String = config.getString("key-store-password")
  @deprecated("Implement and setup a SslManagersProvider instead of using this setting.", "2.6.6")
  val SSLKeyPassword: String = config.getString("key-password")
  @deprecated("Implement and setup a SslManagersProvider instead of using this setting.", "2.6.6")
  val SSLTrustStorePassword: String = config.getString("trust-store-password")

  // Set up RNG for the SSLEngine
  @deprecated("Use SecureRandomFactory directly.", "2.6.6")
  val SSLRandomNumberGenerator: String = config.getString("random-number-generator")

  // This exists as a field for backwards compat only (see usages below)
  private val keyStoreProviders: JksManagerProviders = new JksManagerProviders(config, loadKeystore _)
  private val rng: SecureRandom = SecureRandomFactory.createSecureRandom(SSLRandomNumberGenerator, log)
  private lazy val sslFactory: SslFactory = new SslFactory(config, keyStoreProviders, rng)(log)
  private lazy val sslContext: SSLContext = sslFactory.sslContext

  /**
   * Subclass may override to customize loading of `KeyStore`
   */
  @deprecated("Implement and setup a SslManagersProvider instead of overriding this method.", "2.6.6")
  protected def loadKeystore(filename: String, password: String): KeyStore =
    JksManagerProviders.loadKeystore(filename, password)

  /**
   * Subclass may override to customize `KeyManager`
   */
  @deprecated("Implement and setup a SslManagersProvider instead of overriding this method.", "2.6.6")
  protected def keyManagers: Array[KeyManager] = keyStoreProviders.keyManagers

  /**
   * Subclass may override to customize `TrustManager`
   */
  @deprecated("Implement and setup a SslManagersProvider instead of overriding this method.", "2.6.6")
  protected def trustManagers: Array[TrustManager] = keyStoreProviders.trustManagers

  // TODO: remove
  @deprecated("Use SecureRandomFactory directly.", "2.6.6")
  def createSecureRandom(): SecureRandom = rng

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    sslFactory.createServerSSLEngine(hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    sslFactory.createClientSSLEngine(hostname, port)

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    NoopSessionVerifier.verifyClientSession(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    NoopSessionVerifier.verifyServerSession(hostname, session)

}

// TODO: is SSLEngineProviderSetup used anywhere?
object SSLEngineProviderSetup {

  /**
   * Scala API: factory for defining a `SSLEngineProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def apply(sslEngineProvider: ExtendedActorSystem => SSLEngineProvider): SSLEngineProviderSetup =
    new SSLEngineProviderSetup(sslEngineProvider)

  /**
   * Java API: factory for defining a `SSLEngineProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def create(
      sslEngineProvider: java.util.function.Function[ExtendedActorSystem, SSLEngineProvider]): SSLEngineProviderSetup =
    apply(sys => sslEngineProvider(sys))

}

/**
 * Setup for defining a `SSLEngineProvider` that is passed in when ActorSystem
 * is created rather than creating one from configured class name. That is useful
 * when the SSLEngineProvider implementation require other external constructor parameters
 * or is created before the ActorSystem is created.
 *
 * Constructor is *Internal API*, use factories in [[SSLEngineProviderSetup]]
 */
class SSLEngineProviderSetup private (val sslEngineProvider: ExtendedActorSystem => SSLEngineProvider) extends Setup
