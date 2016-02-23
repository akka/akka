package akka.remote

import akka.testkit._
import akka.remote.transport.netty.SSLSettings

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978ConfigSpec extends AkkaSpec("""
    akka.remote.netty.ssl.security {
      random-number-generator = "AES128CounterSecureRNG"
    }
    """) with ImplicitSender with DefaultTimeout {

  "SSL Remoting" must {
    "be able to parse these extra Netty config elements" in {
      val settings = new SSLSettings(system.settings.config.getConfig("akka.remote.netty.ssl.security"))

      settings.SSLKeyStore should ===(Some("keystore"))
      settings.SSLKeyStorePassword should ===(Some("changeme"))
      settings.SSLKeyPassword should ===(Some("changeme"))
      settings.SSLTrustStore should ===(Some("truststore"))
      settings.SSLTrustStorePassword should ===(Some("changeme"))
      settings.SSLProtocol should ===(Some("TLSv1.2"))
      settings.SSLEnabledAlgorithms should ===(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      settings.SSLRandomNumberGenerator should ===(Some("AES128CounterSecureRNG"))
    }
  }
}
