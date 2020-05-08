/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pki.pem

import java.io.File
import java.security.PrivateKey

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PEMPrivateKeyLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  private def load(resource: String): PrivateKey = {
    val resourceUrl = getClass.getClassLoader.getResource(resource)
    resourceUrl.getProtocol should ===("file")
    val path = new File(resourceUrl.toURI).getAbsolutePath
    PEMPrivateKeyLoader.load(path).fold(fail(_), identity)
  }

  "The PEM Private Key loader" should {
    "decode the same key in PKCS#1 and PKCS#8 formats" in {
      val pkcs1 = load("pkcs1.pem")
      val pkcs8 = load("pkcs8.pem")
      pkcs1 should ===(pkcs8)
    }

    "parse multi primes" in {
      load("multi-prime-pkcs1.pem")
      // Not much we can verify here - I actually think the default JDK security implementation ignores the extra
      // primes, and it fails to parse a multi-prime PKCS#8 key.
    }

  }

}
