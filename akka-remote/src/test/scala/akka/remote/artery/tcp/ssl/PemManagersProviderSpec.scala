/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 *
 */
class PemManagersProviderSpec extends AnyWordSpec with Matchers {

  "A PemManagersProvider" must {

    "load stores reading files setup in config" in {
      // Needs https://github.com/akka/akka/pull/29039
      pending
//    withFiles("keystore", "truststore", "changeme") {
//      provider =>
//        provider.trustManagers.length must be(1)
//        provider.keyManagers.length must be(1)
//        provider.nodeCertificate.getSubjectDN.getName must be("CN=akka-remote, O=Lightbend, ST=web, C=ZA")
//    }
    }
  }

//  private def withFiles(keyStoreName: String, trustStoreName: String, password: String)(
//      block: (PemManagersProvider) => Unit) = {
//    val filesConfig = {
//      val keyStore = getClass.getClassLoader.getResource(keyStoreName).getPath
//      val trustStore = getClass.getClassLoader.getResource(trustStoreName).getPath
//
//      ConfigFactory.parseString(s"""
//        key-file = "$keyStore"
//        cert-file = "$trustStore"
//        ca-cert-file = "$password"
//    """)
//    }
//    block(new PemManagersProvider(filesConfig))
//  }

}
