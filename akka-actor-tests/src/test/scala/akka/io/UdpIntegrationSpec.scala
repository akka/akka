/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.{ InetAddress, InetSocketAddress, ProtocolFamily, StandardProtocolFamily, NetworkInterface }
import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
import akka.util.ByteString
import akka.actor.{ ActorSystem, ActorRef }
import akka.io.Udp._
import akka.TestUtils._
import scala.Serializable
import scala.collection.immutable
import akka.io.Inet.SocketOption
import akka.io.Inet.SO.{ ReuseAddress, JoinGroup }

trait UdpSpecHelpers { this: AkkaSpec ⇒
  val addresses = temporaryServerAddresses(3, udp = true)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), Bind(handler, address))
    commander.expectMsg(Bound(address))
    commander.sender()
  }

  def bindUdp(family: ProtocolFamily, options: immutable.Traversable[SocketOption], address: InetSocketAddress, handler: ActorRef): TestProbe = {
    val commander = TestProbe()
    commander.send(IO(Udp), Bind(handler, address, options, Some(family)))
    commander
  }

  val simpleSender: ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), SimpleSender)
    commander.expectMsg(SimpleSenderReady)
    commander.sender()
  }
}

class UdpIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
  """) with ImplicitSender with UdpSpecHelpers {

  "The UDP Fire-and-Forget implementation" must {

    "be able to send without binding" in {
      val serverAddress = addresses(0)
      val server = bindUdp(serverAddress, testActor)
      val data = ByteString("To infinity and beyond!")
      simpleSender ! Send(data, serverAddress)

      expectMsgType[Received].data should be(data)

    }

    "be able to send several packet back and forth with binding" in {
      val serverAddress = addresses(1)
      val clientAddress = addresses(2)
      val server = bindUdp(serverAddress, testActor)
      val client = bindUdp(clientAddress, testActor)
      val data = ByteString("Fly little packet!")

      def checkSendingToClient(): Unit = {
        server ! Send(data, clientAddress)
        expectMsgPF() {
          case Received(d, a) ⇒
            d should be(data)
            a should be(serverAddress)
        }
      }
      def checkSendingToServer(): Unit = {
        client ! Send(data, serverAddress)
        expectMsgPF() {
          case Received(d, a) ⇒
            d should be(data)
            a should be(clientAddress)
        }
      }

      (0 until 20).foreach(_ ⇒ checkSendingToServer())
      (0 until 20).foreach(_ ⇒ checkSendingToClient())
      (0 until 20).foreach { i ⇒
        if (i % 2 == 0) checkSendingToServer()
        else checkSendingToClient()
      }
    }
  }

  "The UDP Protocol Family option" must {

    "be able to bind to IPv4 and IPv6 addresses" in {
      val ipv4Address = new InetSocketAddress("127.0.0.1", 0)
      bindUdp(StandardProtocolFamily.INET, Nil, ipv4Address, testActor).expectMsgType[Bound]

      val ipv6Address = new InetSocketAddress("::1", 0)
      bindUdp(StandardProtocolFamily.INET6, Nil, ipv6Address, testActor).expectMsgType[Bound]
    }

    "fail when the protocol family is unsupported" in {
      val unsupported = new ProtocolFamily with Serializable { def name() = "fake protocol" }
      val address = new InetSocketAddress("", 0)
      bindUdp(unsupported, Nil, address, testActor).expectMsgType[CommandFailed]
    }
  }
}

class UdpNoSerializeIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
    akka.actor.serialize-messages = off
  """) with ImplicitSender with UdpSpecHelpers {

  "The UDP Protocol Family option" must {
    "be able to join an IPv4 multicast group" in {
      val multicastAddress = new InetSocketAddress("127.0.0.1", 0)
      val group = InetAddress.getByName("224.0.0.1")
      val interf = NetworkInterface.getByInetAddress(InetAddress.getLocalHost)
      bindUdp(StandardProtocolFamily.INET, List(JoinGroup(group, interf)), multicastAddress, testActor).expectMsgType[Bound]
    }
  }
}
