/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import scala.annotation.tailrec
import scala.collection.immutable
import akka.testkit.{ AkkaSpec, TestProbe }
import akka.actor.ActorRef
import akka.io.Inet.SocketOption
import akka.testkit.SocketUtil._
import Tcp._
import akka.actor.ActorSystem
import akka.dispatch.ExecutionContexts

trait TcpIntegrationSpecSupport { _: AkkaSpec ⇒

  class TestSetup(shouldBindServer: Boolean = true, runClientInExtraSystem: Boolean = true) {
    val clientSystem =
      if (runClientInExtraSystem) {
        val res = ActorSystem("TcpIntegrationSpec-client", system.settings.config)
        // terminate clientSystem after server system
        system.whenTerminated.onComplete { _ ⇒ res.terminate() }(ExecutionContexts.sameThreadExecutionContext)
        res
      } else system
    val bindHandler = TestProbe()
    val endpoint = temporaryServerAddress()

    if (shouldBindServer) bindServer()

    def bindServer(): Unit = {
      val bindCommander = TestProbe()
      bindCommander.send(IO(Tcp), Bind(bindHandler.ref, endpoint, options = bindOptions))
      bindCommander.expectMsg(Bound(endpoint))
    }

    def establishNewClientConnection(): (TestProbe, ActorRef, TestProbe, ActorRef) = {
      val connectCommander = TestProbe()(clientSystem)
      connectCommander.send(IO(Tcp)(clientSystem), Connect(endpoint, options = connectOptions))
      val Connected(`endpoint`, localAddress) = connectCommander.expectMsgType[Connected]
      val clientHandler = TestProbe()(clientSystem)
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
