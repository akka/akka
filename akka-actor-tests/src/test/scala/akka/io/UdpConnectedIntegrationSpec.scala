/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.InetSocketAddress

import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import akka.util.ByteString
import akka.actor.ActorRef
import akka.testkit.SocketUtil.temporaryServerAddresses

class UdpConnectedIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
    """) with ImplicitSender {

  val addresses = temporaryServerAddresses(5, udp = true)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), Udp.Bind(handler, address))
    commander.expectMsg(Udp.Bound(address))
    commander.sender()
  }

  def connectUdp(
      localAddress: Option[InetSocketAddress],
      remoteAddress: InetSocketAddress,
      handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(UdpConnected), UdpConnected.Connect(handler, remoteAddress, localAddress, Nil))
    commander.expectMsg(UdpConnected.Connected)
    commander.sender()
  }

  "The UDP connection oriented implementation" must {

    "be able to send and receive without binding" in {
      val serverAddress = addresses(0)
      val server = bindUdp(serverAddress, testActor)
      val data1 = ByteString("To infinity and beyond!")
      val data2 = ByteString("All your datagram belong to us")
      connectUdp(localAddress = None, serverAddress, testActor) ! UdpConnected.Send(data1)

      val clientAddress = expectMsgPF() {
        case Udp.Received(d, a) =>
          d should ===(data1)
          a
      }

      server ! Udp.Send(data2, clientAddress)

      expectMsgType[UdpConnected.Received].data should ===(data2)
    }

    "be able to send and receive with binding" in {
      val serverAddress = addresses(1)
      val clientAddress = addresses(2)
      val server = bindUdp(serverAddress, testActor)
      val data1 = ByteString("To infinity and beyond!")
      val data2 = ByteString("All your datagram belong to us")
      connectUdp(Some(clientAddress), serverAddress, testActor) ! UdpConnected.Send(data1)

      expectMsgPF() {
        case Udp.Received(d, a) =>
          d should ===(data1)
          a should ===(clientAddress)
      }

      server ! Udp.Send(data2, clientAddress)

      expectMsgType[UdpConnected.Received].data should ===(data2)
    }

    "be able to unbind and bind again successfully" in {
      val serverAddress = addresses(3)
      val clientAddress = addresses(4)
      val server1 = bindUdp(serverAddress, testActor)

      val data1 = ByteString("test")
      val client = connectUdp(Some(clientAddress), serverAddress, testActor)

      client ! UdpConnected.Send(data1)
      expectMsgType[Udp.Received].data should ===(data1)

      server1 ! Udp.Unbind
      expectMsg(Udp.Unbound)

      // Reusing the address
      bindUdp(serverAddress, testActor)

      client ! UdpConnected.Send(data1)
      expectMsgType[Udp.Received].data should ===(data1)
    }

    // #26903
    "be able to send and receive when server goes away (and comes back)" in {
      val addresses = temporaryServerAddresses(2, udp = true)
      val serverAddress = addresses(0)
      val clientAddress = addresses(1)
      val server = bindUdp(serverAddress, testActor)
      val data1 = ByteString("To infinity and beyond!")

      val clientCommander = connectUdp(Some(clientAddress), serverAddress, testActor)
      clientCommander ! UdpConnected.Send(data1)

      expectMsg(Udp.Received(data1, clientAddress))

      server ! Udp.Unbind
      expectMsg(Udp.Unbound)
      Thread.sleep(1000) // if it stops that takes a bit of time, give it that time

      // bug was that the commander would fail on next read/write
      clientCommander ! UdpConnected.Send(ByteString("data to trigger fail"), 1)
      expectMsg(1)

      // and at this time the commander would have stopped
      clientCommander ! UdpConnected.Send(ByteString("data to trigger fail"), 2)
      expectMsg(2)

      // when a new server appears at the same port it it should be able to receive
      val serverIncarnation2 = bindUdp(serverAddress, testActor)
      val dataToNewIncarnation = ByteString("Data to new incarnation")
      clientCommander ! UdpConnected.Send(dataToNewIncarnation, 3)
      expectMsg(3)
      expectMsg(Udp.Received(dataToNewIncarnation, clientAddress))

      serverIncarnation2 ! Udp.Unbind
    }

  }

}
