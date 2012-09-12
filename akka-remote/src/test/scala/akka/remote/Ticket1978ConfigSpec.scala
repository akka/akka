package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import scala.concurrent.duration._
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
      SslSettings.SSLKeyStore must be(Some("keystore"))
      SslSettings.SSLKeyStorePassword must be(Some("changeme"))
      SslSettings.SSLTrustStore must be(Some("truststore"))
      SslSettings.SSLTrustStorePassword must be(Some("changeme"))
      SslSettings.SSLProtocol must be(Some("TLSv1"))
      SslSettings.SSLEnabledAlgorithms must be(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      SslSettings.SSLRandomSource must be(None)
      SslSettings.SSLRandomNumberGenerator must be(None)
    }
  }
}
