package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import scala.concurrent.duration._
import java.util.ArrayList
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

      settings.SSLKeyStore must be(Some("keystore"))
      settings.SSLKeyStorePassword must be(Some("changeme"))
      settings.SSLTrustStore must be(Some("truststore"))
      settings.SSLTrustStorePassword must be(Some("changeme"))
      settings.SSLProtocol must be(Some("TLSv1"))
      settings.SSLEnabledAlgorithms must be(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      settings.SSLRandomNumberGenerator must be(Some("AES128CounterSecureRNG"))
    }
  }
}
