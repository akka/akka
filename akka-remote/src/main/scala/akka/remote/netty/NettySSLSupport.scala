/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import _root_.java.security.Provider
import _root_.java.security.SecureRandom
import _root_.java.security.Security
import org.jboss.netty.handler.ssl.SslHandler
import javax.net.ssl.{ KeyManagerFactory, TrustManager, TrustManagerFactory, SSLContext }
import akka.remote.{ RemoteTransportException }
import akka.event.LoggingAdapter
import java.io.{ IOException, FileNotFoundException, FileInputStream }
import java.security.{ SecureRandom, GeneralSecurityException, KeyStore }
import akka.security.provider.AkkaProvider
import com.sun.xml.internal.bind.v2.model.core.NonElement

/**
 * Used for adding SSL support to Netty pipeline
 * Internal use only
 */
private object NettySSLSupport {
  /**
   * Construct a SSLHandler which can be inserted into a Netty server/client pipeline
   */
  def apply(settings: NettySettings, log: LoggingAdapter, isClient: Boolean): SslHandler = {
    if (isClient) initialiseClientSSL(settings, log)
    else initialiseServerSSL(settings, log)
  }

  private def initialiseCustomSecureRandom(settings: NettySettings, log: LoggingAdapter): SecureRandom = {
    /**
     * According to this bug report: http://bugs.sun.com/view_bug.do;jsessionid=ff625daf459fdffffffffcd54f1c775299e0?bug_id=6202721
     * Using /dev/./urandom is only necessary when using SHA1PRNG on Linux
     * <quote>Use 'new SecureRandom()' instead of 'SecureRandom.getInstance("SHA1PRNG")'</quote> to avoid having problems
     */
    settings.SSLRandomSource match {
      case Some(path) ⇒ System.setProperty("java.security.egd", path)
      case None       ⇒
    }

    val rng = settings.SSLRandomNumberGenerator match {
      case Some(generator) ⇒ generator match {
        case "AES128CounterRNGFast" ⇒ {
          log.debug("SSL random number generator set to: AES128CounterRNGFast")
          val akka = new AkkaProvider
          Security.addProvider(akka)
          SecureRandom.getInstance("AES128CounterRNGFast", akka)
        }
        case "AES128CounterRNGSecure" ⇒ {
          log.debug("SSL random number generator set to: AES128CounterRNGSecure")
          val akka = new AkkaProvider
          Security.addProvider(akka)
          SecureRandom.getInstance("AES128CounterRNGSecure", akka)
        }
        case "AES256CounterRNGSecure" ⇒ {
          log.debug("SSL random number generator set to: AES256CounterRNGSecure")
          val akka = new AkkaProvider
          Security.addProvider(akka)
          SecureRandom.getInstance("AES256CounterRNGSecure", akka)
        }
        case "SHA1PRNG" ⇒ {
          log.debug("SSL random number generator set to: SHA1PRNG")
          // This needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
          // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
          SecureRandom.getInstance("SHA1PRNG")
        }
        case _ ⇒ {
          log.debug("SSL random number generator set to default: SecureRandom")
          new SecureRandom
        }
      }
      case None ⇒ {
        log.debug("SSL random number generator not set. Setting to default: SecureRandom")
        new SecureRandom
      }
    }
    // prevent stall on first access
    rng.nextInt()
    rng
  }

  private def initialiseClientSSL(settings: NettySettings, log: LoggingAdapter): SslHandler = {
    log.debug("Client SSL is enabled, initialising ...")
    val sslContext: Option[SSLContext] = {
      (settings.SSLTrustStore, settings.SSLTrustStorePassword, settings.SSLProtocol) match {
        case (Some(trustStore), Some(password), Some(protocol)) ⇒ constructClientContext(settings, log, trustStore, password, protocol)
        case (trustStore, password, protocol) ⇒
          val msg = "SSL trust store settings went missing. [trust-store: %s] [trust-store-password: %s] [protocol: %s]"
            .format(trustStore, password, protocol)
          throw new GeneralSecurityException(msg)
      }
    }
    sslContext match {
      case Some(context) ⇒ {
        log.debug("Using client SSL context to create SSLEngine ...")
        val sslEngine = context.createSSLEngine
        sslEngine.setUseClientMode(true)
        sslEngine.setEnabledCipherSuites(settings.SSLSupportedAlgorithms.toArray.map(_.toString))
        new SslHandler(sslEngine)
      }
      case None ⇒ {
        val msg = "Failed to initialise client SSL because SSL context could not be found. " +
          "Make sure your settings are correct: [trust-store: %s] [trust-store-password: %s] [protocol: %s]"
          .format(settings.SSLTrustStore, settings.SSLTrustStorePassword, settings.SSLProtocol)
        throw new GeneralSecurityException(msg)
      }
    }
  }

  private def constructClientContext(settings: NettySettings, log: LoggingAdapter, trustStorePath: String, trustStorePassword: String, protocol: String): Option[SSLContext] = {
    try {
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
      val stream = new FileInputStream(trustStorePath)
      trustStore.load(stream, trustStorePassword.toCharArray)
      trustManagerFactory.init(trustStore)
      val trustManagers: Array[TrustManager] = trustManagerFactory.getTrustManagers
      val sslContext = SSLContext.getInstance(protocol)
      sslContext.init(null, trustManagers, initialiseCustomSecureRandom(settings, log))
      Some(sslContext)
    } catch {
      case e: FileNotFoundException    ⇒ throw new RemoteTransportException("Client SSL connection could not be established because trust store could not be loaded", e)
      case e: IOException              ⇒ throw new RemoteTransportException("Client SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException ⇒ throw new RemoteTransportException("Client SSL connection could not be established because SSL context could not be constructed", e)
    }
  }

  private def initialiseServerSSL(settings: NettySettings, log: LoggingAdapter): SslHandler = {
    log.debug("Server SSL is enabled, initialising ...")
    val sslContext: Option[SSLContext] = {
      (settings.SSLKeyStore, settings.SSLKeyStorePassword, settings.SSLProtocol) match {
        case (Some(keyStore), Some(password), Some(protocol)) ⇒ constructServerContext(settings, log, keyStore, password, protocol)
        case (keyStore, password, protocol) ⇒
          val msg = "SSL key store settings went missing. [key-store: %s] [key-store-password: %s] [protocol: %s]".format(keyStore, password, protocol)
          throw new GeneralSecurityException(msg)
      }
    }
    sslContext match {
      case Some(context) ⇒ {
        log.debug("Using server SSL context to create SSLEngine ...")
        val sslEngine = context.createSSLEngine
        sslEngine.setUseClientMode(false)
        sslEngine.setEnabledCipherSuites(settings.SSLSupportedAlgorithms.toArray.map(_.toString))
        new SslHandler(sslEngine)
      }
      case None ⇒ {
        val msg = "Failed to initialise server SSL because SSL context could not be found. " +
          "Make sure your settings are correct: [key-store: %s] [key-store-password: %s] [protocol: %s]"
          .format(settings.SSLKeyStore, settings.SSLKeyStorePassword, settings.SSLProtocol)
        throw new GeneralSecurityException(msg)
      }
    }
  }

  private def constructServerContext(settings: NettySettings, log: LoggingAdapter, keyStorePath: String, keyStorePassword: String, protocol: String): Option[SSLContext] = {
    try {
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
      val stream = new FileInputStream(keyStorePath)
      keyStore.load(stream, keyStorePassword.toCharArray)
      factory.init(keyStore, keyStorePassword.toCharArray)
      val sslContext = SSLContext.getInstance(protocol)
      sslContext.init(factory.getKeyManagers, null, initialiseCustomSecureRandom(settings, log))
      Some(sslContext)
    } catch {
      case e: FileNotFoundException    ⇒ throw new RemoteTransportException("Server SSL connection could not be established because key store could not be loaded", e)
      case e: IOException              ⇒ throw new RemoteTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException ⇒ throw new RemoteTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
    }
  }
}
