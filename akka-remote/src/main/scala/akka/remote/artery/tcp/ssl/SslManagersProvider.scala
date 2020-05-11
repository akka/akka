package akka.remote.artery.tcp.ssl

import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

import scala.util.Try

trait SslManagersProvider {
  def trustManagers: Array[TrustManager]
  def keyManagers: Array[KeyManager]

  val caCertificate: Certificate
  val peerCertificate: X509Certificate
}

// This constructor is provided for backwards compat in ConfigSSLEngineProvider
final class JksManagerProviders(config: Config, loadKeystore: (String, String) => KeyStore)
    extends SslManagersProvider {

  // Preferred constructor
  def this(config: Config) =
    this(config, JksManagerProviders.loadKeystore)

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
}

private[akka] object JksManagerProviders {
  def loadKeystore(filename: String, password: String): KeyStore = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    val fin = Files.newInputStream(Paths.get(filename))
    try keyStore.load(fin, password.toCharArray)
    finally Try(fin.close())
    keyStore
  }

}
