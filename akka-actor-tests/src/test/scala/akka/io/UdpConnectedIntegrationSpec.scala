/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.InetSocketAddress
import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
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

  def connectUdp(localAddress: Option[InetSocketAddress], remoteAddress: InetSocketAddress, handler: ActorRef): ActorRef = {
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
        case Udp.Received(d, a) ⇒
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
        case Udp.Received(d, a) ⇒
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
      val server2 = bindUdp(serverAddress, testActor)

      client ! UdpConnected.Send(data1)
      expectMsgType[Udp.Received].data should ===(data1)
    }

  }

}
