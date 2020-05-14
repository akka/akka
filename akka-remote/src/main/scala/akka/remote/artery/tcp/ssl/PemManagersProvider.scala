/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

import scala.util.Try

final class PemManagersProvider(config: Config) extends SslManagersProvider {
  // TODO: support password-protected PKCS#8
  private val SSLKeyFile: String = config.getString("key-file")
  private val SSLCertFile: String = config.getString("cert-file")
  private val SSLCACertFile: String = config.getString("ca-cert-file")

  private val certFactory = CertificateFactory.getInstance("X.509")

  private val caCertificate: Certificate = loadCertificate(SSLCACertFile)
  val peerCertificate: X509Certificate = loadCertificate(SSLCertFile).asInstanceOf[X509Certificate]

  // data is read once. To force a reload create a new instance of this SslManagersProvider
  val trustManagers: Array[TrustManager] = {
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType )
    trustStore.load(null)
    trustStore.setCertificateEntry("cacert", caCertificate)

    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(trustStore)
    tmf.getTrustManagers
  }

  // data is read once. To force a reload create a new instance of this SslManagersProvider
  val keyManagers: Array[KeyManager] = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null)
    keyStore.setCertificateEntry("cacert", caCertificate)
    keyStore.setCertificateEntry("cert", peerCertificate)

    // Load the private key
    val privateKey = PemManagersProvider.loadPrivateKey(new File(SSLKeyFile))
    keyStore.setKeyEntry("private-key", privateKey, "changeit".toCharArray, Array(peerCertificate, caCertificate))

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, "changeit".toCharArray)
    kmf.getKeyManagers
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private def loadCertificate(filename: String): Certificate = {
    val fin = new FileInputStream(filename)
    try certFactory.generateCertificate(fin)
    finally Try(fin.close())
  }
}

// This is a stub for code introduced in https://github.com/akka/akka/pull/29039
// TODO: remove
object PemManagersProvider {
  val loadPrivateKey: File => PrivateKey =
    PemManagersProvider.readPath.andThen(PemManagersProvider.decode).andThen(PemManagersProvider.load)
  val readPath: File => String = null
  val decode: String => DERData = null
  val load: DERData => PrivateKey = null
  @ApiMayChange
  class DERData(val label: String, val bytes: Array[Byte])

}
