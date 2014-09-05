/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io

import java.net.{Inet6Address, InetSocketAddress, NetworkInterface, StandardProtocolFamily}
import java.nio.channels.DatagramChannel
import scala.util.Random

import akka.actor.{ActorSystem, Props}
import akka.io.Udp
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfter, WordSpecLike}

import scala.collection.JavaConversions.enumerationAsScalaIterator

class ScalaUdpMulticastSpec extends TestKit(ActorSystem("ScalaUdpMulticastSpec")) with WordSpecLike with BeforeAndAfter {

  "listener" should {
    "send message back to sink" in {
      val Some(ipv6Iface) = NetworkInterface.getNetworkInterfaces.collectFirst {
        case iface if iface.getInetAddresses.exists(_.isInstanceOf[Inet6Address]) => iface
      }

      // host assigned link local multicast address http://tools.ietf.org/html/rfc3307#section-4.3.2
      // generate a random 32 bit multicast address with the high order bit set
      val randomAddress: String = (Random.nextInt().abs.toLong | (1L << 31)).toHexString.toUpperCase
      val group = randomAddress.grouped(4).mkString("FF02::", ":", "")
      val port = TestUtils.temporaryUdpIpv6Port(ipv6Iface)
      val msg = "ohi"
      val sink = testActor
      val iface = ipv6Iface.getName

      val listener = system.actorOf(Props(classOf[Listener], iface, group, port, sink))
      expectMsgType[Udp.Bound]
      val sender = system.actorOf(Props(classOf[Sender], iface, group, port, msg))
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
