/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

import akka.annotation.InternalApi
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

import scala.util.Try

// TODO: docs
trait SslManagersProvider {
  def trustManagers: Array[TrustManager]
  def keyManagers: Array[KeyManager]

  val caCertificate: Certificate
  val peerCertificate: X509Certificate
}

// TODO: docs
final class JksManagersProvider private[tcp] (config: Config) extends SslManagersProvider {

  val SSLKeyStore: String = config.getString("key-store")
  val SSLTrustStore: String = config.getString("trust-store")
  val SSLKeyStorePassword: String = config.getString("key-store-password")
  val SSLKeyPassword: String = config.getString("key-password")
  val SSLTrustStorePassword: String = config.getString("trust-store-password")

  val caCertificate: Certificate = ???
  val peerCertificate: X509Certificate = ???

  def trustManagers: Array[TrustManager] = {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(loadKeystore(SSLTrustStore, SSLTrustStorePassword))
    trustManagerFactory.getTrustManagers
  }

  def keyManagers: Array[KeyManager] = {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    factory.init(loadKeystore(SSLKeyStore, SSLKeyStorePassword), SSLKeyPassword.toCharArray)
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
