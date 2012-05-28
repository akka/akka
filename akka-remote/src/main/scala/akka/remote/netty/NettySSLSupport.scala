/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import org.jboss.netty.handler.ssl.SslHandler
import com.sun.xml.internal.bind.v2.model.core.NonElement
import com.sun.xml.internal.ws.resources.SoapMessages
import javax.net.ssl.{ KeyManagerFactory, TrustManager, TrustManagerFactory, SSLContext }
import akka.remote.{ RemoteClientError, RemoteTransportException, RemoteServerError }
import java.security.{ GeneralSecurityException, SecureRandom, KeyStore }
import java.io.{ IOException, FileNotFoundException, FileInputStream }

object NettySSLSupport {
  /**
   * Construct a SSLHandler which can be inserted into a Netty server/client pipeline
   */
  def apply(settings: NettySettings, netty: NettyRemoteTransport, isClient: Boolean): SslHandler = {
    if (isClient) initialiseClientSSL(settings, netty)
    else initialiseServerSSL(settings, netty)
  }

  private def initialiseClientSSL(settings: NettySettings, netty: NettyRemoteTransport): SslHandler = {
    netty.log.debug("Client SSL is enabled, initialising ...")
    val sslContext: Option[SSLContext] = {
      (settings.SSLTrustStore, settings.SSLTrustStorePassword, settings.SSLProtocol) match {
        case (Some(trustStore), Some(password), Some(protocol)) ⇒ constructClientContext(settings, netty, trustStore, password, protocol)
        case _ ⇒ throw new GeneralSecurityException("Could not find all SSL trust store settings")
      }
    }
    sslContext match {
      case Some(context) ⇒ {
        netty.log.debug("Using client SSL context to create SSLEngine ...")
        val sslEngine = context.createSSLEngine
        sslEngine.setUseClientMode(true)
        sslEngine.setEnabledCipherSuites(settings.SSLSupportedAlgorithms.toArray.map(_.toString))
        new SslHandler(sslEngine)
      }
      case None ⇒ throw new GeneralSecurityException("Failed to initialise client SSL")
    }
  }

  private def constructClientContext(settings: NettySettings, netty: NettyRemoteTransport, trustStorePath: String, trustStorePassword: String, protocol: String): Option[SSLContext] = {
    try {
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
      val stream = new FileInputStream(trustStorePath)
      trustStore.load(stream, trustStorePassword.toCharArray)
      trustManagerFactory.init(trustStore)
      val trustManagers: Array[TrustManager] = trustManagerFactory.getTrustManagers
      val sslContext = SSLContext.getInstance(protocol)
      sslContext.init(null, trustManagers, new SecureRandom())
      Some(sslContext)
    } catch {
      case e: FileNotFoundException ⇒ {
        val exception = new RemoteTransportException("Client SSL connection could not be established because trust store could not be loaded", e)
        netty.notifyListeners(RemoteClientError(exception, netty, netty.address))
        throw exception
      }
      case e: IOException ⇒ {
        val exception = new RemoteTransportException("Client SSL connection could not be established because: " + e.getMessage, e)
        netty.notifyListeners(RemoteClientError(exception, netty, netty.address))
        throw exception
      }
      case e: GeneralSecurityException ⇒ {
        val exception = new RemoteTransportException("Client SSL connection could not be established because SSL context could not be constructed", e)
        netty.notifyListeners(RemoteClientError(exception, netty, netty.address))
        throw exception
      }
    }
  }

  private def initialiseServerSSL(settings: NettySettings, netty: NettyRemoteTransport): SslHandler = {
    netty.log.debug("Server SSL is enabled, initialising ...")
    val sslContext: Option[SSLContext] = {
      (settings.SSLKeyStore, settings.SSLKeyStorePassword, settings.SSLProtocol) match {
        case (Some(keyStore), Some(password), Some(protocol)) ⇒ constructServerContext(settings, netty, keyStore, password, protocol)
        case _ ⇒ throw new GeneralSecurityException("Could not find all SSL key store settings")
      }
    }
    sslContext match {
      case Some(context) ⇒ {
        netty.log.debug("Using server SSL context to create SSLEngine ...")
        val sslEngine = context.createSSLEngine
        sslEngine.setUseClientMode(false)
        sslEngine.setEnabledCipherSuites(settings.SSLSupportedAlgorithms.toArray.map(_.toString))
        new SslHandler(sslEngine)
      }
      case None ⇒ throw new GeneralSecurityException("Failed to initialise server SSL")
    }
  }

  private def constructServerContext(settings: NettySettings, netty: NettyRemoteTransport, keyStorePath: String, keyStorePassword: String, protocol: String): Option[SSLContext] = {
    try {
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
      val stream = new FileInputStream(keyStorePath)
      keyStore.load(stream, keyStorePassword.toCharArray)
      factory.init(keyStore, keyStorePassword.toCharArray)
      val sslContext = SSLContext.getInstance(protocol)
      sslContext.init(factory.getKeyManagers, null, new SecureRandom())
      Some(sslContext)
    } catch {
      case e: FileNotFoundException ⇒ {
        val exception = new RemoteTransportException("Server SSL connection could not be established because key store could not be loaded", e)
        netty.notifyListeners(RemoteServerError(exception, netty))
        throw exception
      }
      case e: IOException ⇒ {
        val exception = new RemoteTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
        netty.notifyListeners(RemoteServerError(exception, netty))
        throw exception
      }
      case e: GeneralSecurityException ⇒ {
        val exception = new RemoteTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
        netty.notifyListeners(RemoteServerError(exception, netty))
        throw exception
      }
    }
  }
}
