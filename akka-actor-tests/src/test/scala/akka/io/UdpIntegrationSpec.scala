/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
import akka.util.ByteString
import akka.actor.ActorRef
import akka.io.Udp._
import akka.io.Inet._
import akka.TestUtils._

class UdpIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on""") with ImplicitSender {

  val addresses = temporaryServerAddresses(4, udp = true)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), Bind(handler, address))
    commander.expectMsg(Bound(address))
    commander.sender
  }

  val simpleSender: ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), SimpleSender)
    commander.expectMsg(SimpleSenderReady)
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

    "call options" in {
      case class AssertCall() extends SocketOption {
        var beforeCalled = 0
        var afterCalled = 0

        override def beforeBind(c: DatagramChannel): Unit = {
          assert(c.socket.isBound === false)
          beforeCalled += 1
        }

        override def afterConnect(c: DatagramChannel): Unit = {
          assert(c.socket.isBound === true)
          afterCalled += 1
        }
      }

      val commander = TestProbe()
      val assertOption = AssertCall()
      commander.send(IO(Udp), Bind(testActor, addresses(3), options = List(assertOption)))
      commander.expectMsg(Bound(addresses(3)))
      assert(assertOption.beforeCalled === 1)
      assert(assertOption.afterCalled === 1)
    }
  }

}
