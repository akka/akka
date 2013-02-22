/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
import akka.io.UdpFF._
import TestUtils._
import akka.util.ByteString
import java.net.InetSocketAddress
import akka.actor.ActorRef

class UdpFFIntegrationSpec extends AkkaSpec("akka.loglevel = INFO") with ImplicitSender {

  val addresses = temporaryServerAddresses(3)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(UdpFF), Bind(handler, address))
    commander.expectMsg(Bound)
    commander.sender
  }

  val simpleSender: ActorRef = {
    val commander = TestProbe()
    commander.send(IO(UdpFF), SimpleSender)
    commander.expectMsg(SimpleSendReady)
    commander.sender
  }

  "The UDP Fire-and-Forget implementation" must {

    "be able to send without binding" in {
      val serverAddress = addresses(0)
      val server = bindUdp(serverAddress, testActor)
      val data = ByteString("To infinity and beyond!")
      simpleSender ! Send(data, serverAddress)

      expectMsgType[Received].data must be === data

    }

    "be able to send with binding" in {
      val serverAddress = addresses(1)
      val clientAddress = addresses(2)
      val server = bindUdp(serverAddress, testActor)
      val client = bindUdp(clientAddress, testActor)
      val data = ByteString("Fly little packet!")

      client ! Send(data, serverAddress)

      expectMsgPF() {
        case Received(d, a) ⇒
          d must be === data
          a must be === clientAddress
      }
      server ! Send(data, clientAddress)
      expectMsgPF() {
        case Received(d, a) ⇒
          d must be === data
          a must be === serverAddress
      }
    }

  }

}
