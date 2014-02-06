/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.testkit.{ TestProbe, AkkaSpec }
import akka.util.ByteString
import akka.TestUtils._
import concurrent.duration._
import Tcp._
import java.net.InetSocketAddress

class TcpIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
    """) with TcpIntegrationSpecSupport {

  "The TCP transport implementation" should {

    "properly bind a test server" in new TestSetup

    "allow connecting to and disconnecting from the test server" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()
      clientHandler.send(clientConnection, Close)
      clientHandler.expectMsg(Closed)
      serverHandler.expectMsg(PeerClosed)
      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "properly handle connection abort from one side" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()
      clientHandler.send(clientConnection, Abort)
      clientHandler.expectMsg(Aborted)
      serverHandler.expectMsgType[ErrorClosed]
      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "properly complete one client/server request/response cycle" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      object Aye extends Event
      object Yes extends Event

      clientHandler.send(clientConnection, Write(ByteString("Captain on the bridge!"), Aye))
      clientHandler.expectMsg(Aye)
      serverHandler.expectMsgType[Received].data.decodeString("ASCII") should be("Captain on the bridge!")

      serverHandler.send(serverConnection, Write(ByteString("For the king!"), Yes))
      serverHandler.expectMsg(Yes)
      clientHandler.expectMsgType[Received].data.decodeString("ASCII") should be("For the king!")

      serverHandler.send(serverConnection, Close)
      serverHandler.expectMsg(Closed)
      clientHandler.expectMsg(PeerClosed)

      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "support waiting for writes with backpressure" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      object Ack extends Event

      serverHandler.send(serverConnection, Write(ByteString(Array.fill[Byte](100000)(0)), Ack))
      serverHandler.expectMsg(Ack)

      expectReceivedData(clientHandler, 100000)

      override def bindOptions = List(SO.SendBufferSize(1024))
      override def connectOptions = List(SO.ReceiveBufferSize(1024))
    }
    "don't report Connected when endpoint isn't responding" in {
      val connectCommander = TestProbe()
      // a "random" endpoint hopefully unavailable
      val endpoint = new InetSocketAddress("10.226.182.48", 23825)
      connectCommander.send(IO(Tcp), Connect(endpoint))
      // expecting CommandFailed or no reply (within timeout)
      val replies = connectCommander.receiveWhile(1.second) { case m: Connected ⇒ m }
      replies should be(Nil)
    }
  }

}
