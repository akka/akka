/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
import akka.io.UdpFF._
import akka.TestUtils
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
            d must be === data
            a must be === serverAddress
        }
      }
      def checkSendingToServer(): Unit = {
        client ! Send(data, serverAddress)
        expectMsgPF() {
          case Received(d, a) ⇒
            d must be === data
            a must be === clientAddress
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

}
