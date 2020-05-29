/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/**
 *
 */
class TlsResourcesSpec extends AnyWordSpec with Matchers {
  import TlsResourcesSpec._
  "example.com certificate family" must {
    "all include `example.com` as SAN" in {
      Set("one", "two", "node", "client").foreach { prefix =>
        val serverCert = loadCert(s"/ssl/$prefix.example.com.crt")
        X509Readers.getAllSubjectNames(serverCert).contains("example.com") mustBe (true)
      }
    }

    "not add `example.com` as SAN on the `island.example.com` certificate" in {
      val serverCert = loadCert(s"/ssl/island.example.com.crt")
      X509Readers.getAllSubjectNames(serverCert).contains("example.com") mustBe (false)
    }

    import scala.collection.JavaConverters._
    val serverAuth = "1.3.6.1.5.5.7.3.1" // TLS Web server authentication
    val clientAuth = "1.3.6.1.5.5.7.3.2" // TLS Web client authentication
    "have certificates usable for client Auth" in {
      Set("node", "client").foreach { prefix =>
        val cert = loadCert(s"/ssl/$prefix.example.com.crt")
        cert.getExtendedKeyUsage.asScala.contains(clientAuth) mustBe (true)
      }
    }

    "have certificates usable for server Auth" in {
      Set("node", "one", "two", "island").foreach { prefix =>
        val serverCert = loadCert(s"/ssl/$prefix.example.com.crt")
        serverCert.getExtendedKeyUsage.asScala.contains(serverAuth) mustBe (true)
      }
    }

    "have an RSA Private Key in PEM format" in {
      val pk = PemManagersProvider.loadPrivateKey(getPrefixed("ssl/node.example.com.pem"))
      pk.getAlgorithm must be("RSA")
    }

  }

}

object TlsResourcesSpec {

  def getPrefixed(resourceName: String): String =
    getClass.getClassLoader.getResource(resourceName).getPath

  private val certFactory = CertificateFactory.getInstance("X.509")
  def loadCert(resourceName: String): X509Certificate = {
    val fin = classOf[X509ReadersSpec].getResourceAsStream(resourceName)
    try certFactory.generateCertificate(fin).asInstanceOf[X509Certificate]
    finally Try(fin.close())
  }
}
