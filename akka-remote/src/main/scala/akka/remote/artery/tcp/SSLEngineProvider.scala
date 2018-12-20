/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery
package tcp

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom

import scala.util.Try

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.setup.Setup
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.stream.TLSRole
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

@ApiMayChange trait SSLEngineProvider {

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
@ApiMayChange class ConfigSSLEngineProvider(
  protected val config: Config,
  protected val log:    MarkerLoggingAdapter) extends SSLEngineProvider {

  def this(system: ActorSystem) = this(
    system.settings.config.getConfig("akka.remote.artery.ssl.config-ssl-engine"),
    Logging.withMarker(system, classOf[ConfigSSLEngineProvider].getName))

  val SSLKeyStore: String = config.getString("key-store")
  val SSLTrustStore: String = config.getString("trust-store")
  val SSLKeyStorePassword: String = config.getString("key-store-password")
  val SSLKeyPassword: String = config.getString("key-password")
  val SSLTrustStorePassword: String = config.getString("trust-store-password")
  val SSLEnabledAlgorithms: Set[String] = immutableSeq(config.getStringList("enabled-algorithms")).to[Set]
  val SSLProtocol: String = config.getString("protocol")
  val SSLRandomNumberGenerator: String = config.getString("random-number-generator")
  val SSLRequireMutualAuthentication: Boolean = config.getBoolean("require-mutual-authentication")
  val HostnameVerification: Boolean = config.getBoolean("hostname-verification")

  private lazy val sslContext: SSLContext = {
    // log hostname verification warning once
    if (HostnameVerification)
      log.debug("TLS/SSL hostname verification is enabled.")
    else
      log.warning(LogMarker.Security, "TLS/SSL hostname verification is disabled. " +
        "Please configure akka.remote.artery.ssl.config-ssl-engine.hostname-verification=on " +
        "and ensure the X.509 certificate on the host is correct to remove this warning. " +
        "See Akka reference documentation for more information.")

    constructContext()
  }

  private def constructContext(): SSLContext = {
    try {
      val rng = createSecureRandom()
      val ctx = SSLContext.getInstance(SSLProtocol)
      ctx.init(keyManagers, trustManagers, rng)
      ctx
    } catch {
      case e: FileNotFoundException ⇒
        throw new SslTransportException("Server SSL connection could not be established because key store could not be loaded", e)
      case e: IOException ⇒
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException ⇒
        throw new SslTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
    }
  }

  /**
   * Subclass may override to customize loading of `KeyStore`
   */
  protected def loadKeystore(filename: String, password: String): KeyStore = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    val fin = Files.newInputStream(Paths.get(filename))
    try keyStore.load(fin, password.toCharArray) finally Try(fin.close())
    keyStore
  }

  /**
   * Subclass may override to customize `KeyManager`
   */
  protected def keyManagers: Array[KeyManager] = {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    factory.init(loadKeystore(SSLKeyStore, SSLKeyStorePassword), SSLKeyPassword.toCharArray)
    factory.getKeyManagers
  }

  /**
   * Subclass may override to customize `TrustManager`
   */
  protected def trustManagers: Array[TrustManager] = {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(loadKeystore(SSLTrustStore, SSLTrustStorePassword))
    trustManagerFactory.getTrustManagers
  }

  def createSecureRandom(): SecureRandom =
    SecureRandomFactory.createSecureRandom(SSLRandomNumberGenerator, log)

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Server, hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Client, hostname, port)

  private def createSSLEngine(role: TLSRole, hostname: String, port: Int): SSLEngine = {
    createSSLEngine(sslContext, role, hostname, port)
  }

  private def createSSLEngine(
    sslContext: SSLContext,
    role:       TLSRole,
    hostname:   String,
    port:       Int): SSLEngine = {

    val engine = sslContext.createSSLEngine(hostname, port)

    if (HostnameVerification) {
      val sslParams = sslContext.getDefaultSSLParameters
      sslParams.setEndpointIdentificationAlgorithm("HTTPS")
      engine.setSSLParameters(sslParams)
    }

    engine.setUseClientMode(role == akka.stream.Client)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))

    if ((role != akka.stream.Client) && SSLRequireMutualAuthentication)
      engine.setNeedClientAuth(true)

    engine
  }

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    None

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    None

}

object SSLEngineProviderSetup {

  /**
   * Scala API: factory for defining a `SSLEngineProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def apply(sslEngineProvider: ExtendedActorSystem ⇒ SSLEngineProvider): SSLEngineProviderSetup =
    new SSLEngineProviderSetup(sslEngineProvider)

  /**
   * Java API: factory for defining a `SSLEngineProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def create(sslEngineProvider: java.util.function.Function[ExtendedActorSystem, SSLEngineProvider]): SSLEngineProviderSetup =
    apply(sys ⇒ sslEngineProvider(sys))

}

/**
 * Setup for defining a `SSLEngineProvider` that is passed in when ActorSystem
 * is created rather than creating one from configured class name. That is useful
 * when the SSLEngineProvider implementation require other external constructor parameters
 * or is created before the ActorSystem is created.
 *
 * Constructor is *Internal API*, use factories in [[SSLEngineProviderSetup()]]
 */
@ApiMayChange class SSLEngineProviderSetup private (
  val sslEngineProvider: ExtendedActorSystem ⇒ SSLEngineProvider) extends Setup

/**
 * INTERNAL API
 */
@InternalApi private[akka] object SecureRandomFactory {
  def createSecureRandom(randomNumberGenerator: String, log: MarkerLoggingAdapter): SecureRandom = {
    val rng = randomNumberGenerator match {
      case s @ ("SHA1PRNG" | "NativePRNG") ⇒
        log.debug("SSL random number generator set to: {}", s)
        // SHA1PRNG needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
        // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
        SecureRandom.getInstance(s)

      case "" | "SecureRandom" ⇒
        log.debug("SSL random number generator set to [SecureRandom]")
        new SecureRandom

      case unknown ⇒
        log.warning(LogMarker.Security, "Unknown SSL random number generator [{}] falling back to SecureRandom", unknown)
        new SecureRandom
    }
    rng.nextInt() // prevent stall on first access
    rng
  }
}
