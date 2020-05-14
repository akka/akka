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
class X509ReadersSpec extends AnyWordSpec with Matchers {
  import X509ReadersSpec._

  "X509Readers" must {
    "read a certificate's name from the CN" in {
      val clientCA = loadCert("/ssl/clientca.crt")
      val client = loadCert("/ssl/client.crt")
      X509Readers.getAllSubjectNames(clientCA) mustBe (Set("clientca"))
      X509Readers.getAllSubjectNames(client) mustBe (Set("client"))
    }

    "read both the CN and the subject alternative names" in {
      val exampleCaCert = loadCert("/ssl/exampleca.crt")
      val serverCert = loadCert("/ssl/example.com.crt")
      X509Readers.getAllSubjectNames(serverCert) mustBe (Set("example.com", "the.example.com"))
      X509Readers.getAllSubjectNames(exampleCaCert) mustBe (Set("exampleCA", "ca-server.example.com"))
    }
  }
}

object X509ReadersSpec {

  private val certFactory = CertificateFactory.getInstance("X.509")
  def loadCert(resourceName: String): X509Certificate = {
    val fin = classOf[X509ReadersSpec].getResourceAsStream(resourceName)
    try certFactory.generateCertificate(fin).asInstanceOf[X509Certificate]
    finally Try(fin.close())
  }
}
