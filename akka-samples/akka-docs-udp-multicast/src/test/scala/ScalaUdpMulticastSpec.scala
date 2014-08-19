/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io

import java.net.{Inet6Address, InetSocketAddress, NetworkInterface, StandardProtocolFamily}
import java.nio.channels.DatagramChannel

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfter, WordSpecLike}

import scala.collection.JavaConversions.enumerationAsScalaIterator

class ScalaUdpMulticastSpec extends TestKit(ActorSystem("ScalaUdpMulticastSpec")) with WordSpecLike with BeforeAndAfter {

  "listener" should {
    "send message back to sink" in {
      val Some(ipv6Iface) = NetworkInterface.getNetworkInterfaces.collectFirst {
        case iface if iface.getInetAddresses.exists(_.isInstanceOf[Inet6Address]) => iface
      }

      val port = TestUtils.temporaryUdpIpv6Port(ipv6Iface)

      val (iface, group, msg, sink) = (ipv6Iface.getName, "FF33::1200", "ohi", testActor)

      val listener = system.actorOf(Props(classOf[Listener], iface, group, port, sink))
      val sender = system.actorOf(Props(classOf[Sender], group, port, msg))

      expectMsg(msg)

      // unbind
      system.stop(listener)
    }
  }

  def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

}

object TestUtils {
  def temporaryUdpIpv6Port(iface: NetworkInterface) = {
    val serverSocket = DatagramChannel.open(StandardProtocolFamily.INET6).socket()
    serverSocket.bind(new InetSocketAddress(iface.getInetAddresses.nextElement(), 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }
}
