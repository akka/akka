/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetSocketAddress

import akka.actor.{ Props, Terminated }
import akka.io.Tcp
import akka.io.Tcp.{ CommandFailed, Connected, PeerClosed, Register }
import akka.io.dns.internal.DnsClient.Answer
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }

class TcpDnsClientSpec extends AkkaSpec with ImplicitSender {
  import TcpDnsClient._

  "The async TCP DNS client" should {
    val exampleRequestMessage = Message(42, MessageFlags())
    val exampleResponseMessage = Message(42, MessageFlags())
    val dnsServerAddress = InetSocketAddress.createUnresolved("foo", 53)
    val localAddress = InetSocketAddress.createUnresolved("localhost", 13441)

    "reconnect when the server closes the connection" in {
      val tcpExtensionProbe = TestProbe()
      val answerProbe = TestProbe()

      val client = system.actorOf(Props(new TcpDnsClient(dnsServerAddress, answerProbe.ref) {
        override val tcp = tcpExtensionProbe.ref
      }))

      client ! exampleRequestMessage

      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
      tcpExtensionProbe.lastSender ! Connected(dnsServerAddress, localAddress)
      expectMsgType[Register]
      val registered = tcpExtensionProbe.lastSender

      expectMsgType[Tcp.Write]
      expectMsgType[Tcp.Write]
      registered ! Tcp.Received(encodeLength(exampleResponseMessage.write().length) ++ exampleResponseMessage.write())

      answerProbe.expectMsg(Answer(42, Nil))

      // When a new request arrived after the connection is closed
      registered ! PeerClosed
      client ! exampleRequestMessage

      // Expect a reconnect
      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
    }
  }
}
