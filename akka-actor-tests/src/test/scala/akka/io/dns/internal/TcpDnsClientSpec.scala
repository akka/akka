/*
 * Copyright (C) 2018-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetSocketAddress

import scala.collection.immutable.Seq

import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.Tcp.{ Connected, PeerClosed, Register }
import akka.io.dns.{ RecordClass, RecordType }
import akka.io.dns.internal.DnsClient.Answer
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }

class TcpDnsClientSpec extends AkkaSpec with ImplicitSender {
  import TcpDnsClient._

  "The async TCP DNS client" should {
    val exampleRequestMessage =
      Message(42, MessageFlags(), questions = Seq(Question("akka.io", RecordType.A, RecordClass.IN)))
    val exampleResponseMessage = Message(42, MessageFlags(answer = true))
    val dnsServerAddress = InetSocketAddress.createUnresolved("foo", 53)
    val localAddress = InetSocketAddress.createUnresolved("localhost", 13441)

    "reconnect when the server closes the connection" in {
      val tcpExtensionProbe = TestProbe()
      val answerProbe = TestProbe()

      val client = system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, answerProbe.ref)))

      client ! exampleRequestMessage

      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
      tcpExtensionProbe.lastSender ! Connected(dnsServerAddress, localAddress)
      expectMsgType[Register]
      val registered = tcpExtensionProbe.lastSender

      expectMsgType[Tcp.Write]
      registered ! Tcp.Received(encodeLength(exampleResponseMessage.write().length) ++ exampleResponseMessage.write())

      answerProbe.expectMsg(Answer(42, Nil))

      // When a new request arrived after the connection is closed
      registered ! PeerClosed
      client ! exampleRequestMessage

      // Expect a reconnect
      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
    }

    "accept a fragmented TCP response" in {
      val tcpExtensionProbe = TestProbe()
      val answerProbe = TestProbe()

      val client = system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, answerProbe.ref)))

      client ! exampleRequestMessage

      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
      tcpExtensionProbe.lastSender ! Connected(dnsServerAddress, localAddress)
      expectMsgType[Register]
      val registered = tcpExtensionProbe.lastSender

      expectMsgType[Tcp.Write]
      val fullResponse = encodeLength(exampleResponseMessage.write().length) ++ exampleResponseMessage.write()
      registered ! Tcp.Received(fullResponse.take(8))
      registered ! Tcp.Received(fullResponse.drop(8))

      answerProbe.expectMsg(Answer(42, Nil))
    }

    "respect backpressure from the TCP actor" in {
      val tcpExtensionProbe = TestProbe()

      val client = system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, ActorRef.noSender)))

      client ! exampleRequestMessage
      client ! exampleRequestMessage.copy(id = 43)

      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
      tcpExtensionProbe.lastSender ! Connected(dnsServerAddress, localAddress)
      expectMsgType[Register]
      val registered = tcpExtensionProbe.lastSender

      val ack = expectMsgType[Tcp.Write].ack
      expectNoMessage()
      registered ! ack

      expectMsgType[Tcp.Write]
    }

    "accept merged TCP responses" in {
      val tcpExtensionProbe = TestProbe()
      val answerProbe = TestProbe()

      val client = system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, answerProbe.ref)))

      client ! exampleRequestMessage
      client ! exampleRequestMessage.copy(id = 43)

      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
      tcpExtensionProbe.lastSender ! Connected(dnsServerAddress, localAddress)
      expectMsgType[Register]
      val registered = tcpExtensionProbe.lastSender

      var ack = expectMsgType[Tcp.Write].ack

      registered ! ack

      ack = expectMsgType[Tcp.Write].ack

      val fullResponse =
        encodeLength(exampleResponseMessage.write().length) ++ exampleResponseMessage.write() ++
        encodeLength(exampleResponseMessage.write().length) ++ exampleResponseMessage.copy(id = 43).write()
      registered ! Tcp.Received(fullResponse.take(8))
      registered ! Tcp.Received(fullResponse.drop(8))

      answerProbe.expectMsg(Answer(42, Nil))
      answerProbe.expectMsg(Answer(43, Nil))
    }

    "report its failure to the outer client" in {
      val tcpExtensionProbe = TestProbe()
      val answerProbe = TestProbe()

      val failToConnectClient =
        system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, answerProbe.ref)))

      failToConnectClient ! exampleRequestMessage

      val connect = tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))

      failToConnectClient ! Tcp.CommandFailed(connect)

      answerProbe.expectMsg(DnsClient.TcpDropped)

      val closesWithErrorClient =
        system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, answerProbe.ref)))

      closesWithErrorClient ! exampleRequestMessage

      tcpExtensionProbe.expectMsg(connect)
      tcpExtensionProbe.lastSender ! Connected(dnsServerAddress, localAddress)
      expectMsgType[Register]
      val registered = tcpExtensionProbe.lastSender

      registered ! Tcp.ErrorClosed("BOOM!")

      answerProbe.expectMsg(DnsClient.TcpDropped)
    }

    "should resort to dropping older requests in response to sufficient backpressure" in {
      val tcpExtensionProbe = TestProbe()
      val connectionProbe = TestProbe()

      val client = system.actorOf(Props(new TcpDnsClient(tcpExtensionProbe.ref, dnsServerAddress, ActorRef.noSender)))

      client ! exampleRequestMessage

      tcpExtensionProbe.expectMsg(Tcp.Connect(dnsServerAddress))
      tcpExtensionProbe.lastSender.tell(Connected(dnsServerAddress, localAddress), connectionProbe.ref)
      connectionProbe.expectMsgType[Register]
      val registered = connectionProbe.lastSender

      var write = connectionProbe.expectMsgType[Tcp.Write]
      write.data.drop(2) shouldBe (exampleRequestMessage.write())

      // Send 20 requests before the ack...
      // - first 11 get added without dropping (buffer is 1 - 11)
      // - drop #1 to make room for #12 (buffer is 2 - 12)
      // - expand for #13 (buffer is 2 - 13)
      // - drop #2 and #3 to make room for #14 and #15 (buffer is 4 - 15)
      // - expand for #16 (buffer is 4 - 16)
      // - drop #4, #5, and #6 to make room for #17, #18, #19 (buffer is 7 - 19)
      // - expand for #20 (buffer is 7 - 20)
      (1 to 20).foreach { i =>
        client ! exampleRequestMessage.copy(id = (exampleRequestMessage.id + i).toShort)
      }

      (7 to 20).foreach { i =>
        registered ! write.ack
        write = connectionProbe.expectMsgType[Tcp.Write]
        write.data.drop(2) shouldBe (exampleRequestMessage.copy(id = (exampleRequestMessage.id + i).toShort).write())
      }
    }
  }
}
