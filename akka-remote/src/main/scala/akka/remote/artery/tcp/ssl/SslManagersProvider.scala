/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.X509Certificate

import akka.annotation.InternalApi
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

import scala.util.Try

/**
 * TODO
 * Implementations of this trait may memoize the keys and certificates. Clients should create a new
 * instance to guarantee data is reloaded or use implementation-specific mechanisms to force a reload.
 */
trait SslManagersProvider {
  def trustManagers: Array[TrustManager]
  def keyManagers: Array[KeyManager]

  val peerCertificate: X509Certificate
}

// TODO: docs
final class JksManagersProvider private[tcp] (config: Config) extends SslManagersProvider {

  val SSLKeyStore: String = config.getString("key-store")
  val SSLTrustStore: String = config.getString("trust-store")
  val SSLKeyStorePassword: String = config.getString("key-store-password")
  val SSLKeyPassword: String = config.getString("key-password")
  val SSLTrustStorePassword: String = config.getString("trust-store-password")

  private def keyStore() = loadKeystore(SSLKeyStore, SSLKeyStorePassword)

  // Take the first non-CA certificate in the keyStore
  // TODO: Improve this adding a setting so users can indicate the `alias` in the keyStore
  //  containing the peer certificate
  val peerCertificate: X509Certificate = {
    import scala.collection.JavaConverters._
    val ks = keyStore()
    ks.aliases()
      .asScala
      .filter(ks.isCertificateEntry)
      .map(ks.getCertificate)
      .map(_.asInstanceOf[X509Certificate])
      // BasicConstraints == -1 means it is a certificate that's not a CA
      .filter(_.getBasicConstraints == -1)
      .next()
  }

  // data is read once. To force a reload create a new instance of this SslManagersProvider
  val trustManagers: Array[TrustManager] = {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(loadKeystore(SSLTrustStore, SSLTrustStorePassword))
    trustManagerFactory.getTrustManagers
  }

  // data is read once. To force a reload create a new instance of this SslManagersProvider
  val keyManagers: Array[KeyManager] = {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    factory.init(keyStore(), SSLKeyPassword.toCharArray)
    factory.getKeyManagers
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private def loadKeystore(filename: String, password: String): KeyStore = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    val fin = Files.newInputStream(Paths.get(filename))
    try keyStore.load(fin, password.toCharArray)
    finally Try(fin.close())
    keyStore
  }

}
