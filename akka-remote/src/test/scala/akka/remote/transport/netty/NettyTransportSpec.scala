package akka.remote.transport.netty

import scala.concurrent.duration._
import java.net.{ InetAddress, InetSocketAddress }
import akka.testkit.SocketUtil
import akka.actor.{ ActorSystem, Address, ExtendedActorSystem }
import akka.remote.BoundAddressesExtension
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.testkit.TestKit

object NettyTransportSpec {
  val commonConfig = ConfigFactory.parseString("""
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
  """)

  def getInternal()(implicit sys: ActorSystem) =
    BoundAddressesExtension(sys).boundAddresses.values.flatten

  def getExternal()(implicit sys: ActorSystem) =
    sys.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  implicit class RichInetSocketAddress(address: InetSocketAddress) {
    def toAkkaAddress(protocol: String)(implicit system: ActorSystem) =
      Address(protocol, system.name, address.getAddress.getHostAddress, address.getPort)
  }

  implicit class RichAkkaAddress(address: Address) {
    def withProtocol(protocol: String)(implicit system: ActorSystem) =
      address.copy(protocol = protocol)
  }
}

class NettyTransportSpec extends WordSpec with Matchers with BindBehaviour {
  import akka.remote.transport.netty.NettyTransportSpec._

  "NettyTransport" should {
    behave like theOneWhoKnowsTheDifferenceBetweenBoundAndRemotingAddress("tcp")
    behave like theOneWhoKnowsTheDifferenceBetweenBoundAndRemotingAddress("udp")

    "bind to a random port" in {
      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp {
          port = 0
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getInternal should contain(getExternal.withProtocol("tcp"))

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }

    "bind to a random port but remoting accepts from a specified port" in {
      val address = SocketUtil.temporaryServerAddress(InetAddress.getLocalHost.getHostAddress, udp = false)

      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp {
          port = ${address.getPort}
          bind-port = 0
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getExternal should be(address.toAkkaAddress("akka.tcp"))
      getInternal should not contain (address.toAkkaAddress("tcp"))

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }

    "bind to a specified port and remoting accepts from a bound port" in {
      val address = SocketUtil.temporaryServerAddress(InetAddress.getLocalHost.getHostAddress, udp = false)

      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp {
          port = 0
          bind-port = ${address.getPort}
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getExternal should be(address.toAkkaAddress("akka.tcp"))
      getInternal should contain(address.toAkkaAddress("tcp"))

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }

    "bind to multiple transports" in {
      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote {
          netty.tcp.port = 0
          netty.udp.port = 0
          enabled-transports = ["akka.remote.netty.tcp", "akka.remote.netty.udp"]
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getInternal should contain(getExternal.withProtocol("tcp"))
      getInternal.size should be(2)

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }

    "bind to all interfaces" in {
      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote {
          netty.tcp.bind-hostname = "0.0.0.0"
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getInternal.flatMap(_.port) should contain(getExternal.port.get)
      getInternal.map(_.host.get should include regex "0.0.0.0".r) // regexp dot is intentional to match IPv4 and 6 addresses

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }
  }
}

trait BindBehaviour { this: WordSpec with Matchers ⇒
  import akka.remote.transport.netty.NettyTransportSpec._

  def theOneWhoKnowsTheDifferenceBetweenBoundAndRemotingAddress(proto: String) = {
    s"bind to default $proto address" in {
      val address = SocketUtil.temporaryServerAddress(udp = proto == "udp")

      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote {
          netty.$proto {
            hostname = ${address.getAddress.getHostAddress}
            port = ${address.getPort}
          }
          enabled-transports = ["akka.remote.netty.$proto"]
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getExternal should be(address.toAkkaAddress(s"akka.$proto"))
      getInternal should contain(address.toAkkaAddress(proto))

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }

    s"bind to specified $proto address" in {
      val address = SocketUtil.temporaryServerAddress(address = "127.0.0.1", udp = proto == "udp")
      val bindAddress = SocketUtil.temporaryServerAddress(address = "127.0.1.1", udp = proto == "udp")

      val bindConfig = ConfigFactory.parseString(s"""
        akka.remote {
          netty.$proto {
            hostname = ${address.getAddress.getHostAddress}
            port = ${address.getPort}

            bind-hostname = ${bindAddress.getAddress.getHostAddress}
            bind-port = ${bindAddress.getPort}
          }
          enabled-transports = ["akka.remote.netty.$proto"]
        }
        """)
      implicit val sys = ActorSystem("sys", bindConfig.withFallback(commonConfig))

      getExternal should be(address.toAkkaAddress(s"akka.$proto"))
      getInternal should contain(bindAddress.toAkkaAddress(proto))

      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }
  }
}
