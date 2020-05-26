/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 *
 */
class PemManagersProviderSpec extends AnyWordSpec with Matchers {

  "A PemManagersProvider" must {

    "load stores reading files setup in config" in {
      withFiles("ssl/pem/pkcs1.pem", "ssl/pem/certificate.pem", "ssl/pem/certificate.pem") { provider =>
        provider.trustManagers.length must be(1)
        provider.keyManagers.length must be(1)
        provider.nodeCertificate.getSubjectDN.getName must be("CN=0d207b68-9a20-4ee8-92cb-bf9699581cf8")
      }
    }

  }

  private def withFiles(keyFile: String, certFile: String, caCertFile: String)(
      block: (PemManagersProvider) => Unit) = {
    val filesConfig = {
      ConfigFactory.parseString(
        s"""
        key-file = "${nameToPath(keyFile)}"
        cert-file = "${nameToPath(certFile)}"
        ca-cert-file = "${nameToPath(caCertFile)}"
    """)
    }
    block(new PemManagersProvider(filesConfig))
  }

  private def nameToPath(name: String): String = getClass.getClassLoader.getResource(name).getPath
}
