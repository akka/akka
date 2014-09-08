/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor.PoisonPill
import akka.io.Tcp._
import akka.testkit.{ TestProbe, AkkaSpec }
import akka.TestUtils._
import akka.util.ByteString
import java.io.IOException
import java.net.{ ServerSocket, InetSocketAddress }
import org.scalatest.concurrent.Timeouts
import scala.concurrent.duration._

import scala.language.postfixOps

class TcpIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
    """) with TcpIntegrationSpecSupport with Timeouts {

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
      // a "random" endpoint hopefully unavailable since it's in the test-net IP range
      val endpoint = new InetSocketAddress("192.0.2.1", 23825)
      connectCommander.send(IO(Tcp), Connect(endpoint))
      // expecting CommandFailed or no reply (within timeout)
      val replies = connectCommander.receiveWhile(1.second) { case m: Connected ⇒ m }
      replies should be(Nil)
    }

    "handle tcp connection actor death properly" in new TestSetup(shouldBindServer = false) {
      val serverSocket = new ServerSocket(endpoint.getPort(), 100, endpoint.getAddress())
      val connectCommander = TestProbe()
      connectCommander.send(IO(Tcp), Connect(endpoint))

      val accept = serverSocket.accept()
      connectCommander.expectMsgType[Connected].remoteAddress should be(endpoint)
      val connectionActor = connectCommander.lastSender
      connectCommander.send(connectionActor, PoisonPill)
      failAfter(3 seconds) {
        try {
          accept.getInputStream.read() should be(-1)
        } catch {
          case e: IOException ⇒ // this is also fine
        }
      }
      verifyActorTermination(connectionActor)
    }
  }
}
