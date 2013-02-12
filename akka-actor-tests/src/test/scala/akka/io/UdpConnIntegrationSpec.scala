/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
import TestUtils._
import akka.util.ByteString
import java.net.InetSocketAddress
import akka.actor.ActorRef

class UdpConnIntegrationSpec extends AkkaSpec("akka.loglevel = INFO") with ImplicitSender {

  def bindUdp(handler: ActorRef): (InetSocketAddress, ActorRef) = {
    val address = temporaryServerAddress()
    val commander = TestProbe()
    commander.send(IO(UdpFF), UdpFF.Bind(handler, address))
    commander.expectMsg(UdpFF.Bound)
    (address, commander.sender)
  }

  def connectUdp(localAddress: Option[InetSocketAddress], remoteAddress: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(UdpConn), UdpConn.Connect(handler, localAddress, remoteAddress, Nil))
    commander.expectMsg(UdpConn.Connected)
    commander.sender
  }

  "The UDP connection oriented implementation" must {

    "be able to send and receive without binding" in {
      val (serverAddress, server) = bindUdp(testActor)
      val data1 = ByteString("To infinity and beyond!")
      val data2 = ByteString("All your datagram belong to us")
      connectUdp(localAddress = None, serverAddress, testActor) ! UdpConn.Send(data1)

      val clientAddress = expectMsgPF() {
        case UdpFF.Received(d, a) ⇒
          d must be === data1
          a
      }

      server ! UdpFF.Send(data2, clientAddress)

      // FIXME: Currently this line fails
      expectMsgPF() {
        case UdpConn.Received(d) ⇒ d must be === data2
      }
    }

    "be able to send and receive with binding" in {
      val clientAddress = temporaryServerAddress()
      val (serverAddress, server) = bindUdp(testActor)
      val data1 = ByteString("To infinity and beyond!")
      val data2 = ByteString("All your datagram belong to us")
      connectUdp(Some(clientAddress), serverAddress, testActor) ! UdpConn.Send(data1)

      expectMsgPF() {
        case UdpFF.Received(d, a) ⇒
          d must be === data1
          a must be === clientAddress
      }

      server ! UdpFF.Send(data2, clientAddress)

      // FIXME: Currently this line fails
      expectMsgPF() {
        case UdpConn.Received(d) ⇒ d must be === data2
      }
    }

  }

}
