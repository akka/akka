/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import com.typesafe.config.ConfigFactory
import akka.actor.{ ActorSystem, Address }
import akka.remote.transport.netty.NettyTransportSpec._

import scala.concurrent.Await
import org.scalatest.WordSpec
import org.scalatest.Matchers

import scala.concurrent.duration.Duration
import akka.testkit.SocketUtil
import java.net.InetAddress

trait BindCanonicalAddressBehaviors {
  this: WordSpec with Matchers =>
  def arteryConnectionTest(transport: String, isUDP: Boolean): Unit = {

    val commonConfig = BindCanonicalAddressSpec.commonConfig(transport)

    "bind to a random port" in {
      val config = ConfigFactory.parseString(s"""
        akka.remote.artery.canonical.port = 0
      """)

      implicit val sys = ActorSystem("sys", config.withFallback(commonConfig))

      getInternal should contain(getExternal)
      Await.result(sys.terminate(), Duration.Inf)
    }

    "bind to a random port but remoting accepts from a specified port" in {
      val address = SocketUtil.temporaryServerAddress(InetAddress.getLocalHost.getHostAddress, udp = isUDP)

      val config = ConfigFactory.parseString(s"""
        akka.remote.artery.canonical.port = ${address.getPort}
        akka.remote.artery.bind.port = 0
      """)

      implicit val sys = ActorSystem("sys", config.withFallback(commonConfig))

      getExternal should ===(address.toAkkaAddress("akka"))
      // May have selected the same random port - bind another in that case while the other still has the canonical port
      val internals =
        if (getInternal.collect { case Address(_, _, _, Some(port)) => port }.toSeq.contains(address.getPort)) {
          val sys2 = ActorSystem("sys", config.withFallback(commonConfig))
          val secondInternals = getInternal()(sys2)
          Await.result(sys2.terminate(), Duration.Inf)
          secondInternals
        } else {
          getInternal
        }
      internals should not contain address.toAkkaAddress("akka")
      Await.result(sys.terminate(), Duration.Inf)
    }

    "bind to a specified bind hostname and remoting aspects from canonical hostname" in {
      val config = ConfigFactory.parseString(s"""
        akka.remote.artery.canonical.port = 0
        akka.remote.artery.canonical.hostname = "127.0.0.1"
        akka.remote.artery.bind.hostname = "localhost"
      """)

      implicit val sys = ActorSystem("sys", config.withFallback(commonConfig))

      getInternal().flatMap(_.host) should contain("localhost")
      getExternal().host should contain("127.0.0.1")
    }

    "bind to a specified port and remoting accepts from a bound port" in {
      val address = SocketUtil.temporaryServerAddress(InetAddress.getLocalHost.getHostAddress, udp = isUDP)

      val config = ConfigFactory.parseString(s"""
        akka.remote.artery.canonical.port = 0
        akka.remote.artery.bind.port = ${address.getPort}
      """)

      implicit val sys = ActorSystem("sys", config.withFallback(commonConfig))

      getExternal should ===(address.toAkkaAddress("akka"))
      getInternal should contain(address.toAkkaAddress("akka"))
    }

    "bind to all interfaces" in {
      val config = ConfigFactory.parseString(s"""
        akka.remote.artery.bind.hostname = "0.0.0.0"
      """)

      implicit val sys = ActorSystem("sys", config.withFallback(commonConfig))

      getInternal.flatMap(_.port) should contain(getExternal.port.get)
      getInternal.map(x => (x.host.get should include).regex("0.0.0.0".r)) // regexp dot is intentional to match IPv4 and 6 addresses

      Await.result(sys.terminate(), Duration.Inf)
    }
  }
}

class BindCanonicalAddressSpec extends WordSpec with Matchers with BindCanonicalAddressBehaviors {
  s"artery with aeron-udp transport" should {
    behave.like(arteryConnectionTest("aeron-udp", isUDP = true))
  }
  s"artery with tcp transport" should {
    behave.like(arteryConnectionTest("tcp", isUDP = false))
  }
  s"artery with tls-tcp transport" should {
    behave.like(arteryConnectionTest("tls-tcp", isUDP = false))
  }
}

object BindCanonicalAddressSpec {
  def commonConfig(transport: String) = ConfigFactory.parseString(s"""
    akka {
      actor.provider = remote
      remote.artery.enabled = true
      remote.artery.transport = "$transport"
    }
  """)
}
