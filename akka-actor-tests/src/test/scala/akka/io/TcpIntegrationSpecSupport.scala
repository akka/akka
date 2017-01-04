/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.io

import scala.annotation.tailrec
import scala.collection.immutable
import akka.testkit.{ AkkaSpec, TestProbe }
import akka.actor.ActorRef
import akka.io.Inet.SocketOption
import akka.testkit.SocketUtil._
import Tcp._

trait TcpIntegrationSpecSupport { _: AkkaSpec ⇒

  class TestSetup(shouldBindServer: Boolean = true) {
    val bindHandler = TestProbe()
    val endpoint = temporaryServerAddress()

    if (shouldBindServer) bindServer()

    def bindServer(): Unit = {
      val bindCommander = TestProbe()
      bindCommander.send(IO(Tcp), Bind(bindHandler.ref, endpoint, options = bindOptions))
      bindCommander.expectMsg(Bound(endpoint))
    }

    def establishNewClientConnection(): (TestProbe, ActorRef, TestProbe, ActorRef) = {
      val connectCommander = TestProbe()
      connectCommander.send(IO(Tcp), Connect(endpoint, options = connectOptions))
      val Connected(`endpoint`, localAddress) = connectCommander.expectMsgType[Connected]
      val clientHandler = TestProbe()
      connectCommander.sender() ! Register(clientHandler.ref)

      val Connected(`localAddress`, `endpoint`) = bindHandler.expectMsgType[Connected]
      val serverHandler = TestProbe()
      bindHandler.sender() ! Register(serverHandler.ref)

      (clientHandler, connectCommander.sender(), serverHandler, bindHandler.sender())
    }

    @tailrec final def expectReceivedData(handler: TestProbe, remaining: Int): Unit =
      if (remaining > 0) {
        val recv = handler.expectMsgType[Received]
        expectReceivedData(handler, remaining - recv.data.size)
      }

    /** allow overriding socket options for server side channel */
    def bindOptions: immutable.Traversable[SocketOption] = Nil

    /** allow overriding socket options for client side channel */
    def connectOptions: immutable.Traversable[SocketOption] = Nil
  }

}
