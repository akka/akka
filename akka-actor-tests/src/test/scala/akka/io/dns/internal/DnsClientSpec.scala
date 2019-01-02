/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.immutable.Seq
import akka.actor.Props
import akka.io.Udp
import akka.io.dns.{ RecordClass, RecordType }
import akka.io.dns.internal.DnsClient.{ Answer, Question4 }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }

class DnsClientSpec extends AkkaSpec with ImplicitSender {
  "The async DNS client" should {
    val exampleRequest = Question4(42, "akka.io")
    val exampleRequestMessage =
      Message(42, MessageFlags(), questions = Seq(Question("akka.io", RecordType.A, RecordClass.IN)))
    val exampleResponseMessage = Message(42, MessageFlags(answer = true))
    val exampleResponse = Answer(42, Nil)
    val dnsServerAddress = InetSocketAddress.createUnresolved("foo", 53)
    val localAddress = InetSocketAddress.createUnresolved("localhost", 13441)

    "not connect to the DNS server over TCP eagerly" in {
      val udpExtensionProbe = TestProbe()
      val tcpClientCreated = new AtomicBoolean(false)

      val client = system.actorOf(Props(new DnsClient(dnsServerAddress) {
        override val udp = udpExtensionProbe.ref

        override def createTcpClient = {
          tcpClientCreated.set(true)
          TestProbe().ref
        }
      }))

      client ! exampleRequest

      udpExtensionProbe.expectMsgType[Udp.Bind]
      udpExtensionProbe.lastSender ! Udp.Bound(InetSocketAddress.createUnresolved("localhost", 41325))

      expectMsgType[Udp.Send]
      client ! Udp.Received(exampleResponseMessage.write(), dnsServerAddress)

      expectMsg(exampleResponse)

      tcpClientCreated.get() should be(false)
    }

    "Fall back to TCP when the UDP response is truncated" in {
      val udpExtensionProbe = TestProbe()
      val tcpClientProbe = TestProbe()

      val client = system.actorOf(Props(new DnsClient(dnsServerAddress) {
        override val udp = udpExtensionProbe.ref

        override def createTcpClient = tcpClientProbe.ref
      }))

      client ! exampleRequest

      udpExtensionProbe.expectMsgType[Udp.Bind]
      udpExtensionProbe.lastSender ! Udp.Bound(InetSocketAddress.createUnresolved("localhost", 41325))

      expectMsgType[Udp.Send]
      client ! Udp.Received(Message(exampleRequest.id, MessageFlags(truncated = true)).write(), dnsServerAddress)

      tcpClientProbe.expectMsg(exampleRequestMessage)
      tcpClientProbe.reply(exampleResponse)

      expectMsg(exampleResponse)
    }
  }
}
