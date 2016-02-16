/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.io.InputStream
import java.security.{ SecureRandom, KeyStore }
import java.security.cert.{ CertificateFactory, Certificate }
import javax.net.ssl._

import akka.http.scaladsl.HttpsConnectionContext

/**
 * These are HTTPS example configurations that take key material from the resources/key folder.
 */
object ExampleHttpContexts {

  // TODO show example how to obtain pre-configured context from ssl-config

  val exampleServerContext = {
    // never put passwords into code!
    val password = "abcdef".toCharArray

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagersForPKCS12Key("keys/server.p12", password), null, new SecureRandom)

    new HttpsConnectionContext(context)
  }
  // a variant of the exampleServerContext that also trusts the custom CA for client certificates
  val exampleServerContextWithClientTrust = {
    // never put passwords into code!
    val password = "abcdef".toCharArray

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagersForPKCS12Key("keys/server.p12", password), trustManagersForX509Certificate("keys/rootCA.crt"), new SecureRandom)

    new HttpsConnectionContext(context)
  }

  val exampleClientContext = {
    val context = SSLContext.getInstance("TLS")
    context.init(null, trustManagersForX509Certificate("keys/rootCA.crt"), new SecureRandom)

    val params = new SSLParameters()
    params.setEndpointIdentificationAlgorithm("https")
    new HttpsConnectionContext(context, sslParameters = Some(params))
  }

  def keyManagersForPKCS12Key(resourceName: String, password: Array[Char]): Array[KeyManager] = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(resourceStream(resourceName), password)

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)
    keyManagerFactory.getKeyManagers
  }

  def trustManagersForX509Certificate(resourceName: String): Array[TrustManager] = {
    val certStore = KeyStore.getInstance(KeyStore.getDefaultType)
    certStore.load(null, null)
    // only do this if you want to accept a custom root CA. Understand what you are doing!
    certStore.setCertificateEntry("ca", loadX509Certificate(resourceName))

    val certManagerFactory = TrustManagerFactory.getInstance("SunX509")
    certManagerFactory.init(certStore)
    certManagerFactory.getTrustManagers
  }

  def resourceStream(resourceName: String): InputStream = {
    val is = getClass.getClassLoader.getResourceAsStream(resourceName)
    require(is ne null, s"Resource $resourceName not found")
    is
  }

  def loadX509Certificate(resourceName: String): Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(resourceStream(resourceName))
}
