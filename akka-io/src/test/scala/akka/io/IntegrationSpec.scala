/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.testkit.{ TestProbe, AkkaSpec }
import akka.actor.ActorRef
import akka.util.ByteString
import Tcp._
import TestUtils._
import collection.immutable
import annotation.tailrec

class IntegrationSpec extends AkkaSpec("akka.loglevel = INFO") {

  "The TCP transport implementation" should {

    "properly bind a test server" in new TestSetup

    "allow connecting to the test server" in new TestSetup {
      establishNewClientConnection()
    }

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
      serverHandler.expectMsgType[ErrorClose]
      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "properly complete one client/server request/response cycle" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      clientHandler.send(clientConnection, Write(ByteString("Captain on the bridge!"), 42))
      clientHandler.expectMsg(42)
      serverHandler.expectMsgType[Received].data.decodeString("ASCII") must be("Captain on the bridge!")

      serverHandler.send(serverConnection, Write(ByteString("For the king!"), 4242))
      serverHandler.expectMsg(4242)
      clientHandler.expectMsgType[Received].data.decodeString("ASCII") must be("For the king!")

      serverHandler.send(serverConnection, Close)
      serverHandler.expectMsg(Closed)
      clientHandler.expectMsg(PeerClosed)

      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    "waiting for writes works with backpressure" in new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      serverHandler.send(serverConnection, Write(ByteString(Array.fill[Byte](100000)(0)), 4223))
      serverHandler.expectMsg(4223)

      expectReceivedData(clientHandler, 100000)

      override def bindOptions: immutable.Traversable[SocketOption] =
        List(SO.SendBufferSize(1024))

      override def connectOptions: immutable.Traversable[SocketOption] =
        List(SO.ReceiveBufferSize(1024))
    }
    //////////////////////////////////////
    ///////// more tests to come /////////
    //////////////////////////////////////
  }

  class TestSetup {
    val bindHandler = TestProbe()
    val endpoint = TemporaryServerAddress()

    bindServer()

    def bindServer(): Unit = {
      val bindCommander = TestProbe()
      bindCommander.send(IO(Tcp), Bind(bindHandler.ref, endpoint, options = bindOptions))
      bindCommander.expectMsg(Bound)
    }

    def establishNewClientConnection(): (TestProbe, ActorRef, TestProbe, ActorRef) = {
      val connectCommander = TestProbe()
      connectCommander.send(IO(Tcp), Connect(endpoint, options = connectOptions))
      val Connected(`endpoint`, localAddress) = connectCommander.expectMsgType[Connected]
      val clientHandler = TestProbe()
      connectCommander.sender ! Register(clientHandler.ref)

      val Connected(`localAddress`, `endpoint`) = bindHandler.expectMsgType[Connected]
      val serverHandler = TestProbe()
      bindHandler.sender ! Register(serverHandler.ref)

      (clientHandler, connectCommander.sender, serverHandler, bindHandler.sender)
    }

    @tailrec final def expectReceivedData(handler: TestProbe, remaining: Int): Unit =
      if (remaining > 0) {
        val recv = handler.expectMsgType[Received]
        expectReceivedData(handler, remaining - recv.data.size)
      }

    /** allow overriding socket options for server side channel */
    def bindOptions: collection.immutable.Traversable[SocketOption] = Nil

    /** allow overriding socket options for client side channel */
    def connectOptions: collection.immutable.Traversable[SocketOption] = Nil
  }

}
