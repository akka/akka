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
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi
private[ssl] object PemManagersProvider {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[ssl] def buildKeyManagers(
      privateKey: PrivateKey,
      cert: X509Certificate,
      cacert: Certificate): Array[KeyManager] = {
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null)

    keyStore.setCertificateEntry("cert", cert)
    keyStore.setCertificateEntry("cacert", cacert)
    keyStore.setKeyEntry("private-key", privateKey, "changeit".toCharArray, Array(cert, cacert))

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, "changeit".toCharArray)
    val keyManagers = kmf.getKeyManagers
    keyManagers
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[ssl] def buildTrustManagers(cacert: Certificate): Array[TrustManager] = {
    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(null)
    trustStore.setCertificateEntry("cacert", cacert)

    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(trustStore)
    tmf.getTrustManagers
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[ssl] def loadPrivateKey(filename: String): PrivateKey = {
    val bytes = Files.readAllBytes(new File(filename).toPath)
    val pemData = new String(bytes, Charset.forName("UTF-8"))
    DERPrivateKeyLoader.load(PEMDecoder.decode(pemData))
  }

  private val certFactory = CertificateFactory.getInstance("X.509")

  /**
   * INTERNAL API
   */
  @InternalApi
  private[ssl] def loadCertificate(filename: String): Certificate = {
    val fin = new FileInputStream(filename)
    try certFactory.generateCertificate(fin)
    finally Try(fin.close())
  }

}
