/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.testkit._
import akka.remote.transport.netty.SSLSettings

class Ticket1978ConfigSpec extends AkkaSpec("""
    akka.remote.netty.ssl.security {
      random-number-generator = "SecureRandom"
    }
    """) with ImplicitSender with DefaultTimeout {

  "SSL Remoting" must {
    "be able to parse these extra Netty config elements" in {
      val settings = new SSLSettings(system.settings.config.getConfig("akka.remote.netty.ssl.security"))

      settings.SSLKeyStore should ===("keystore")
      settings.SSLKeyStorePassword should ===("changeme")
      settings.SSLKeyPassword should ===("changeme")
      settings.SSLTrustStore should ===("truststore")
      settings.SSLTrustStorePassword should ===("changeme")
      settings.SSLProtocol should ===("TLSv1.2")
      settings.SSLEnabledAlgorithms should ===(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      settings.SSLRandomNumberGenerator should ===("SecureRandom")
    }
  }
}
