/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Test JksManagersProvider with multiple input formats (all produced using the JVM's `keytool`)
 */
class JksManagersProviderSpec extends AnyWordSpec with Matchers {

  "A JksManagersProvider" must {

    "load keystore/JKS stores reading files setup in config" in withFiles("keystore", "truststore", "changeme") {
      provider =>
        provider.trustManagers.length must be(1)
        provider.keyManagers.length must be(1)
        provider.nodeCertificate.getSubjectDN.getName must be("CN=akka-remote, O=Lightbend, ST=web, C=ZA")
    }

    "load keystore/PKCS12 stores reading files setup in config" in withFiles(
      "ssl/client.p12",
      "ssl/clientca.p12",
      "kLnCu3rboe") { provider =>
      provider.trustManagers.length must be(1)
      provider.keyManagers.length must be(1)
      provider.nodeCertificate.getSubjectDN.getName must be(
        "CN=client, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US")
    }

  }

  private def withFiles(keyStoreName: String, trustStoreName: String, password: String)(
      block: (JksManagersProvider) => Unit) = {
    val filesConfig = {
      val keyStore = getClass.getClassLoader.getResource(keyStoreName).getPath
      val trustStore = getClass.getClassLoader.getResource(trustStoreName).getPath

      ConfigFactory.parseString(s"""
        key-store = "$keyStore"
        trust-store = "$trustStore"
        key-password = "$password"
        key-store-password = "$password"
        trust-store-password = "$password"
    """)
    }
    block(new JksManagersProvider(filesConfig))
  }

}
