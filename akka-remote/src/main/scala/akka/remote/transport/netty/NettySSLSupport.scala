/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.transport.netty

import akka.ConfigurationException
import akka.event.LoggingAdapter
import akka.japi.Util._
import akka.remote.RemoteTransportException
import akka.remote.security.provider.AkkaProvider
import com.typesafe.config.Config
import java.io.{ IOException, FileNotFoundException, FileInputStream }
import java.security._
import javax.net.ssl.{ KeyManagerFactory, TrustManager, TrustManagerFactory, SSLContext }
import org.jboss.netty.handler.ssl.SslHandler
import scala.util.Try

/**
 * INTERNAL API
 */
private[akka] class SSLSettings(config: Config) {
  import config.{ getString, getStringList }

  val SSLKeyStore = getString("key-store")
  val SSLTrustStore = getString("trust-store")
  val SSLKeyStorePassword = getString("key-store-password")
  val SSLKeyPassword = getString("key-password")

  val SSLTrustStorePassword = getString("trust-store-password")

  val SSLEnabledAlgorithms = immutableSeq(getStringList("enabled-algorithms")).to[Set]

  val SSLProtocol = getString("protocol")

  val SSLRandomNumberGenerator = getString("random-number-generator")
}

/**
 * INTERNAL API
 *
 * Used for adding SSL support to Netty pipeline
 */
private[akka] object NettySSLSupport {

  Security addProvider AkkaProvider

  /**
   * Construct a SSLHandler which can be inserted into a Netty server/client pipeline
   */
  def apply(settings: SSLSettings, log: LoggingAdapter, isClient: Boolean): SslHandler =
    if (isClient) initializeClientSSL(settings, log) else initializeServerSSL(settings, log)

  def initializeCustomSecureRandom(rngName: String, log: LoggingAdapter): SecureRandom = {
    val rng = rngName match {
      case r @ ("AES128CounterSecureRNG" | "AES256CounterSecureRNG") ⇒
        log.debug("SSL random number generator set to: {}", r)
        SecureRandom.getInstance(r, AkkaProvider)
      case r @ ("AES128CounterInetRNG" | "AES256CounterInetRNG") ⇒
        log.warning("SSL random number generator {} is deprecated, " +
          "use AES128CounterSecureRNG or AES256CounterSecureRNG instead", r)
        SecureRandom.getInstance(r, AkkaProvider)
      case s @ ("SHA1PRNG" | "NativePRNG") ⇒
        log.debug("SSL random number generator set to: " + s)
        // SHA1PRNG needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
        // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
        SecureRandom.getInstance(s)

      case "" ⇒
        log.debug("SSLRandomNumberGenerator not specified, falling back to SecureRandom")
        new SecureRandom

      case unknown ⇒
        log.warning("Unknown SSLRandomNumberGenerator [{}] falling back to SecureRandom", unknown)
        new SecureRandom
    }
    rng.nextInt() // prevent stall on first access
    rng
  }

  def initializeClientSSL(settings: SSLSettings, log: LoggingAdapter): SslHandler = {
    log.debug("Client SSL is enabled, initialising ...")

    def constructClientContext(settings: SSLSettings, log: LoggingAdapter, trustStorePath: String, trustStorePassword: String, protocol: String): Option[SSLContext] =
      try {
        val rng = initializeCustomSecureRandom(settings.SSLRandomNumberGenerator, log)
        val trustManagers: Array[TrustManager] = {
          val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
          trustManagerFactory.init({
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
            val fin = new FileInputStream(trustStorePath)
            try trustStore.load(fin, trustStorePassword.toCharArray) finally Try(fin.close())
            trustStore
          })
          trustManagerFactory.getTrustManagers
        }
        Option(SSLContext.getInstance(protocol)) map { ctx ⇒ ctx.init(null, trustManagers, rng); ctx }
      } catch {
        case e: FileNotFoundException    ⇒ throw new RemoteTransportException("Client SSL connection could not be established because trust store could not be loaded", e)
        case e: IOException              ⇒ throw new RemoteTransportException("Client SSL connection could not be established because: " + e.getMessage, e)
        case e: GeneralSecurityException ⇒ throw new RemoteTransportException("Client SSL connection could not be established because SSL context could not be constructed", e)
      }

    constructClientContext(settings, log, settings.SSLTrustStore, settings.SSLTrustStorePassword, settings.SSLProtocol) match {
      case Some(context) ⇒
        log.debug("Using client SSL context to create SSLEngine ...")
        new SslHandler({
          val sslEngine = context.createSSLEngine
          sslEngine.setUseClientMode(true)
          sslEngine.setEnabledCipherSuites(settings.SSLEnabledAlgorithms.toArray)
          sslEngine.setEnabledProtocols(Array(settings.SSLProtocol))
          sslEngine
        })
      case None ⇒
        throw new GeneralSecurityException(
          """Failed to initialize client SSL because SSL context could not be found." +
              "Make sure your settings are correct: [trust-store: %s] [trust-store-password: %s] [protocol: %s]""".format(
            settings.SSLTrustStore,
            settings.SSLTrustStorePassword,
            settings.SSLProtocol))
    }
  }

  def initializeServerSSL(settings: SSLSettings, log: LoggingAdapter): SslHandler = {
    log.debug("Server SSL is enabled, initialising ...")

    def constructServerContext(settings: SSLSettings, log: LoggingAdapter, keyStorePath: String, keyStorePassword: String, keyPassword: String, protocol: String): Option[SSLContext] =
      try {
        val rng = initializeCustomSecureRandom(settings.SSLRandomNumberGenerator, log)
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        factory.init({
          val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
          val fin = new FileInputStream(keyStorePath)
          try keyStore.load(fin, keyStorePassword.toCharArray) finally Try(fin.close())
          keyStore
        }, keyPassword.toCharArray)

        val trustManagers: Array[TrustManager] = {
          val pwd = settings.SSLTrustStorePassword.toCharArray
          val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
          trustManagerFactory.init({
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
            val fin = new FileInputStream(settings.SSLTrustStore)
            try trustStore.load(fin, pwd) finally Try(fin.close())
            trustStore
          })
          trustManagerFactory.getTrustManagers
        }
        Option(SSLContext.getInstance(protocol)) map { ctx ⇒ ctx.init(factory.getKeyManagers, trustManagers, rng); ctx }
      } catch {
        case e: FileNotFoundException    ⇒ throw new RemoteTransportException("Server SSL connection could not be established because key store could not be loaded", e)
        case e: IOException              ⇒ throw new RemoteTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
        case e: GeneralSecurityException ⇒ throw new RemoteTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
      }

    constructServerContext(settings, log, settings.SSLKeyStore, settings.SSLKeyStorePassword, settings.SSLKeyPassword, settings.SSLProtocol) match {
      case Some(context) ⇒
        log.debug("Using server SSL context to create SSLEngine ...")
        val sslEngine = context.createSSLEngine
        sslEngine.setUseClientMode(false)
        sslEngine.setEnabledCipherSuites(settings.SSLEnabledAlgorithms.toArray)
        new SslHandler(sslEngine)
      case None ⇒ throw new GeneralSecurityException(
        """Failed to initialize server SSL because SSL context could not be found.
           Make sure your settings are correct: [key-store: %s] [key-store-password: %s] [protocol: %s]""".format(
          settings.SSLKeyStore,
          settings.SSLKeyStorePassword,
          settings.SSLProtocol))
    }
  }
}
