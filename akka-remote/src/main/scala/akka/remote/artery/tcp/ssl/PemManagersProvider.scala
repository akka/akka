/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import akka.annotation.InternalApi
import akka.pki.pem.DERPrivateKeyLoader
import akka.pki.pem.PEMDecoder
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

import scala.util.Try

// TODO: docs
final class PemManagersProvider private[tcp] (
    val SSLKeyFile: String,
    val SSLCertFile: String,
    val SSLCACertFile: String)
    extends SslManagersProvider {
  // TODO: support password-protected PKCS#8
  def this(config: Config) {
    this(
      SSLKeyFile = config.getString("key-file"),
      SSLCertFile = config.getString("cert-file"),
      SSLCACertFile = config.getString("ca-cert-file"))
  }

  private val certFactory = CertificateFactory.getInstance("X.509")

  private val caCertificate: Certificate = loadCertificate(SSLCACertFile)
  val nodeCertificate: X509Certificate = loadCertificate(SSLCertFile).asInstanceOf[X509Certificate]

  // data is read once. To force a reload create a new instance of this SslManagersProvider
  val trustManagers: Array[TrustManager] = {
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
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
    keyStore.setCertificateEntry("cert", nodeCertificate)

    // Load the private key
    val privateKey = loadPrivateKey(SSLKeyFile)
    keyStore.setKeyEntry("private-key", privateKey, "changeit".toCharArray, Array(nodeCertificate, caCertificate))

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, "changeit".toCharArray)
    kmf.getKeyManagers
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private def loadPrivateKey(filename: String): PrivateKey = {
    val bytes = Files.readAllBytes(new File(filename).toPath)
    val pemData = new String(bytes, Charset.forName("UTF-8"))
    DERPrivateKeyLoader.load(PEMDecoder.decode(pemData))
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
