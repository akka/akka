/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.testkit.AkkaSpec
import akka.util.ByteString
import Tcp._
import TestUtils._
import akka.testkit.EventFilter
import java.io.IOException

class TcpIntegrationSpec extends AkkaSpec("akka.loglevel = INFO") with TcpIntegrationSpecSupport {

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
      EventFilter[IOException](occurrences = 1) intercept {
        clientHandler.send(clientConnection, Abort)
      }
      clientHandler.expectMsg(Aborted)
      serverHandler.expectMsgType[ErrorClosed]
      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "properly complete one client/server request/response cycle" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      clientHandler.send(clientConnection, Write(ByteString("Captain on the bridge!"), 'Aye))
      clientHandler.expectMsg('Aye)
      serverHandler.expectMsgType[Received].data.decodeString("ASCII") must be("Captain on the bridge!")

      serverHandler.send(serverConnection, Write(ByteString("For the king!"), 'Yes))
      serverHandler.expectMsg('Yes)
      clientHandler.expectMsgType[Received].data.decodeString("ASCII") must be("For the king!")

      serverHandler.send(serverConnection, Close)
      serverHandler.expectMsg(Closed)
      clientHandler.expectMsg(PeerClosed)

      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "support waiting for writes with backpressure" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      serverHandler.send(serverConnection, Write(ByteString(Array.fill[Byte](100000)(0)), 'Ack))
      serverHandler.expectMsg('Ack)

      expectReceivedData(clientHandler, 100000)

      override def bindOptions = List(SO.SendBufferSize(1024))
      override def connectOptions = List(SO.ReceiveBufferSize(1024))
    }
  }

}
