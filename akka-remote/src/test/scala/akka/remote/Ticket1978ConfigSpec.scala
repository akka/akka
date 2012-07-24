package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import akka.remote.netty.NettyRemoteTransport
import java.util.ArrayList

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978ConfigSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote.netty {
    hostname = localhost
    port = 0
  }
}
""") with ImplicitSender with DefaultTimeout {

  "SSL Remoting" must {
    "be able to parse these extra Netty config elements" in {
      val settings =
        system.asInstanceOf[ExtendedActorSystem]
          .provider.asInstanceOf[RemoteActorRefProvider]
          .transport.asInstanceOf[NettyRemoteTransport]
          .settings
      import settings._

      EnableSSL must be(false)
      SSLKeyStore must be(Some("keystore"))
      SSLKeyStorePassword must be(Some("changeme"))
      SSLTrustStore must be(Some("truststore"))
      SSLTrustStorePassword must be(Some("changeme"))
      SSLProtocol must be(Some("TLSv1"))
      SSLEnabledAlgorithms must be(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      SSLRandomSource must be(None)
      SSLRandomNumberGenerator must be(None)
    }
  }
}
