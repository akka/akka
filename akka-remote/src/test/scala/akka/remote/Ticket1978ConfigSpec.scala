package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import scala.concurrent.duration._
import akka.remote.netty.{ SSLSettings, NettyRemoteTransport }
import java.util.ArrayList

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978ConfigSpec extends AkkaSpec with ImplicitSender with DefaultTimeout {

  val cfg = ConfigFactory.parseString("""
    ssl-settings {
      key-store = "keystore"
      trust-store = "truststore"
      key-store-password = "changeme"
      trust-store-password = "changeme"
      protocol = "TLSv1"
      random-number-generator = "AES128CounterSecureRNG"
      enabled-algorithms = [TLS_RSA_WITH_AES_128_CBC_SHA]
      sha1prng-random-source = "/dev/./urandom"
    }""")

  "SSL Remoting" must {
    "be able to parse these extra Netty config elements" in {
      val settings = new SSLSettings(cfg.getConfig("ssl-settings"))

      settings.SSLKeyStore must be(Some("keystore"))
      settings.SSLKeyStorePassword must be(Some("changeme"))
      settings.SSLTrustStore must be(Some("truststore"))
      settings.SSLTrustStorePassword must be(Some("changeme"))
      settings.SSLProtocol must be(Some("TLSv1"))
      settings.SSLEnabledAlgorithms must be(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      settings.SSLRandomSource must be(Some("/dev/./urandom"))
      settings.SSLRandomNumberGenerator must be(Some("AES128CounterSecureRNG"))
    }
  }
}
