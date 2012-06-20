/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import org.jboss.netty.handler.ssl.SslHandler
import javax.net.ssl.{ KeyManagerFactory, TrustManager, TrustManagerFactory, SSLContext }
import akka.remote.RemoteTransportException
import akka.event.LoggingAdapter
import java.io.{ IOException, FileNotFoundException, FileInputStream }
import akka.security.provider.AkkaProvider
import java.security._

/**
 * Used for adding SSL support to Netty pipeline
 * Internal use only
 */
private[akka] object NettySSLSupport {

  Security addProvider AkkaProvider

  /**
   * Construct a SSLHandler which can be inserted into a Netty server/client pipeline
   */
  def apply(settings: NettySettings, log: LoggingAdapter, isClient: Boolean): SslHandler =
    if (isClient) initializeClientSSL(settings, log) else initializeServerSSL(settings, log)

  def initializeCustomSecureRandom(rngName: Option[String], sourceOfRandomness: Option[String], log: LoggingAdapter): SecureRandom = {
    /**
     * According to this bug report: http://bugs.sun.com/view_bug.do?bug_id=6202721
     * Using /dev/./urandom is only necessary when using SHA1PRNG on Linux
     * <quote>Use 'new SecureRandom()' instead of 'SecureRandom.getInstance("SHA1PRNG")'</quote> to avoid having problems
     */
    sourceOfRandomness foreach { path ⇒
      System.setProperty("java.security.egd", path)
      System.setProperty("securerandom.source", path)
    }

    val rng = rngName match {
      case Some(r @ ("AES128CounterRNGFast" | "AES128CounterRNGSecure" | "AES256CounterRNGSecure")) ⇒
        log.debug("SSL random number generator set to: {}", r)
        SecureRandom.getInstance(r, AkkaProvider)
      case Some(s @ ("SHA1PRNG" | "NativePRNG")) ⇒
        log.debug("SSL random number generator set to: " + s)
        // SHA1PRNG needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
        // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
        SecureRandom.getInstance(s)
      case Some(unknown) ⇒
        log.debug("Unknown SSLRandomNumberGenerator [{}] falling back to SecureRandom", unknown)
        new SecureRandom
      case None ⇒
        log.debug("SSLRandomNumberGenerator not specified, falling back to SecureRandom")
        new SecureRandom
    }
    rng.nextInt() // prevent stall on first access
    rng
  }

  def initializeClientSSL(settings: NettySettings, log: LoggingAdapter): SslHandler = {
    log.debug("Client SSL is enabled, initialising ...")

    def constructClientContext(settings: NettySettings, log: LoggingAdapter, trustStorePath: String, trustStorePassword: String, protocol: String): Option[SSLContext] =
      try {
        val rng = initializeCustomSecureRandom(settings.SSLRandomNumberGenerator, settings.SSLRandomSource, log)
        val trustManagers: Array[TrustManager] = {
          val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
          trustManagerFactory.init({
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
            val fin = new FileInputStream(trustStorePath)
            try trustStore.load(fin, trustStorePassword.toCharArray) finally fin.close()
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

    ((settings.SSLTrustStore, settings.SSLTrustStorePassword, settings.SSLProtocol) match {
      case (Some(trustStore), Some(password), Some(protocol)) ⇒ constructClientContext(settings, log, trustStore, password, protocol)
      case (trustStore, password, protocol) ⇒ throw new GeneralSecurityException(
        "One or several SSL trust store settings are missing: [trust-store: %s] [trust-store-password: %s] [protocol: %s]".format(
          trustStore,
          password,
          protocol))
    }) match {
      case Some(context) ⇒
        log.debug("Using client SSL context to create SSLEngine ...")
        new SslHandler({
          val sslEngine = context.createSSLEngine
          sslEngine.setUseClientMode(true)
          sslEngine.setEnabledCipherSuites(settings.SSLEnabledAlgorithms.toArray)
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

  def initializeServerSSL(settings: NettySettings, log: LoggingAdapter): SslHandler = {
    log.debug("Server SSL is enabled, initialising ...")

    def constructServerContext(settings: NettySettings, log: LoggingAdapter, keyStorePath: String, keyStorePassword: String, protocol: String): Option[SSLContext] =
      try {
        val rng = initializeCustomSecureRandom(settings.SSLRandomNumberGenerator, settings.SSLRandomSource, log)
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        factory.init({
          val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
          val fin = new FileInputStream(keyStorePath)
          try keyStore.load(fin, keyStorePassword.toCharArray) finally fin.close()
          keyStore
        }, keyStorePassword.toCharArray)
        Option(SSLContext.getInstance(protocol)) map { ctx ⇒ ctx.init(factory.getKeyManagers, null, rng); ctx }
      } catch {
        case e: FileNotFoundException    ⇒ throw new RemoteTransportException("Server SSL connection could not be established because key store could not be loaded", e)
        case e: IOException              ⇒ throw new RemoteTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
        case e: GeneralSecurityException ⇒ throw new RemoteTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
      }

    ((settings.SSLKeyStore, settings.SSLKeyStorePassword, settings.SSLProtocol) match {
      case (Some(keyStore), Some(password), Some(protocol)) ⇒ constructServerContext(settings, log, keyStore, password, protocol)
      case (keyStore, password, protocol) ⇒ throw new GeneralSecurityException(
        "SSL key store settings went missing. [key-store: %s] [key-store-password: %s] [protocol: %s]".format(keyStore, password, protocol))
    }) match {
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
