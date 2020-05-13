package akka.remote.artery.tcp.ssl

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import akka.annotation.ApiMayChange
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

final class PemManagerProviders(config: Config) extends SslManagersProvider {
  // TODO: support password-protected PKCS#8
  private val SSLKeyFile: String = config.getString("key-file")
  private val SSLCertFile: String = config.getString("cert-file")
  private val SSLCACertFile: String = config.getString("ca-cert-file")

  private val certFactory = CertificateFactory.getInstance("X.509")

  val caCertificate: Certificate =
    certFactory.generateCertificate(new FileInputStream(SSLCACertFile))
  val peerCertificate: X509Certificate =
    certFactory.generateCertificate(new FileInputStream(SSLCertFile)).asInstanceOf[X509Certificate]

  override def trustManagers: Array[TrustManager] = {
    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(null)
    trustStore.setCertificateEntry("cacert", caCertificate)

    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(trustStore)
    tmf.getTrustManagers
  }

  override def keyManagers: Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null)
    // Load the private key
    val privateKey = PemManagerProviders.loadPrivateKey(new File(SSLKeyFile))

    keyStore.setCertificateEntry("cert", peerCertificate)
    keyStore.setCertificateEntry("cacert", caCertificate)
    keyStore.setKeyEntry("private-key", privateKey, "changeit".toCharArray, Array(peerCertificate, caCertificate))

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, "changeit".toCharArray)
    kmf.getKeyManagers
  }

}

// This is a stub for code introduced in https://github.com/akka/akka/pull/29039
// TODO: remove
object PemManagerProviders {
  val loadPrivateKey: File => PrivateKey =
    PemManagerProviders.readPath.andThen(PemManagerProviders.decode).andThen(PemManagerProviders.load)
  val readPath: File => String = ???
  val decode: String => DERData = ???
  val load: DERData => PrivateKey = ???
  @ApiMayChange
  class DERData(val label: String, val bytes: Array[Byte])

}
