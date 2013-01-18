/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.testkit.{ TestProbe, AkkaSpec }
import akka.actor.ActorRef
import akka.util.ByteString
import Tcp._
import TestUtils._

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
      bindCommander.send(IO(Tcp), Bind(bindHandler.ref, endpoint))
      bindCommander.expectMsg(Bound)
    }

    def establishNewClientConnection(): (TestProbe, ActorRef, TestProbe, ActorRef) = {
      val connectCommander = TestProbe()
      connectCommander.send(IO(Tcp), Connect(endpoint))
      val Connected(`endpoint`, localAddress) = connectCommander.expectMsgType[Connected]
      val clientHandler = TestProbe()
      connectCommander.sender ! Register(clientHandler.ref)

      val Connected(`localAddress`, `endpoint`) = bindHandler.expectMsgType[Connected]
      val serverHandler = TestProbe()
      bindHandler.sender ! Register(serverHandler.ref)

      (clientHandler, connectCommander.sender, serverHandler, bindHandler.sender)
    }
  }

}