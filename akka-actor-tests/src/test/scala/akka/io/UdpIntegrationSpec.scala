/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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

  val addresses = temporaryServerAddresses(6, udp = true)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), Bind(handler, address))
    commander.expectMsg(Bound(address))
    commander.sender()
  }

  val simpleSender: ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), SimpleSender)
    commander.expectMsg(SimpleSenderReady)
    commander.sender()
  }

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

    "call SocketOption.beforeBind method before bind." in {
      val commander = TestProbe()
      val assertOption = AssertBeforeBind()
      commander.send(IO(Udp), Bind(testActor, addresses(3), options = List(assertOption)))
      commander.expectMsg(Bound(addresses(3)))
      assert(assertOption.beforeCalled === 1)
    }

    "call SocketOption.afterConnect method after binding." in {
      val commander = TestProbe()
      val assertOption = AssertAfterConnect()
      commander.send(IO(Udp), Bind(testActor, addresses(4), options = List(assertOption)))
      commander.expectMsg(Bound(addresses(4)))
      assert(assertOption.afterCalled === 1)
    }

    "call DatagramChannelCreator.create method when opening channel" in {
      val commander = TestProbe()
      val assertOption = AssertOpenDatagramChannel()
      commander.send(IO(Udp), Bind(testActor, addresses(5), options = List(assertOption)))
      commander.expectMsg(Bound(addresses(5)))
      assert(assertOption.openCalled === 1)
    }
  }

}

private case class AssertBeforeBind() extends SocketOption {
  var beforeCalled = 0

  override def beforeBind(c: DatagramChannel) = {
    assert(!c.socket.isBound)
    beforeCalled += 1
  }
}

private case class AssertAfterConnect() extends SocketOption {
  var afterCalled = 0

  override def afterConnect(c: DatagramChannel) = {
    assert(c.socket.isBound)
    afterCalled += 1
  }
}

private case class AssertOpenDatagramChannel() extends DatagramChannelCreator {
  var openCalled = 0

  override def create() = {
    openCalled += 1
    super.create()
  }
}
