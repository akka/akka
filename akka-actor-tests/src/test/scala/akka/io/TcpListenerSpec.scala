/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.Socket
import scala.concurrent.duration._
import akka.actor.{ Terminated, SupervisorStrategy, Actor, Props }
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec }
import TcpSelector._
import Tcp._
import akka.testkit.EventFilter

class TcpListenerSpec extends AkkaSpec("akka.io.tcp.batch-accept-limit = 2") {

  "A TcpListener" must {

    "register its ServerSocketChannel with its selector" in new TestSetup

    "let the Bind commander know when binding is completed" in new TestSetup {
      listener ! Bound
      bindCommander.expectMsg(Bound)
    }

    "accept acceptable connections and register them with its parent" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()
      attemptConnectionToEndpoint()
      attemptConnectionToEndpoint()

      // since the batch-accept-limit is 2 we must only receive 2 accepted connections
      listener ! ChannelAcceptable

      parent.expectMsg(AcceptInterest)
      selectorRouter.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ /* ok */ }
      selectorRouter.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ /* ok */ }
      selectorRouter.expectNoMsg(100.millis)

      // and pick up the last remaining connection on the next ChannelAcceptable
      listener ! ChannelAcceptable
      selectorRouter.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ /* ok */ }
    }

    "react to Unbind commands by replying with Unbound and stopping itself" in new TestSetup {
      bindListener()

      val unbindCommander = TestProbe()
      unbindCommander.send(listener, Unbind)

      unbindCommander.expectMsg(Unbound)
      parent.expectMsgType[Terminated].actor must be(listener)
    }

    "drop an incoming connection if it cannot be registered with a selector" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()

      listener ! ChannelAcceptable
      val channel = selectorRouter.expectMsgType[RegisterIncomingConnection].channel
      channel.isOpen must be(true)

      EventFilter.warning(pattern = "selector capacity limit", occurrences = 1) intercept {
        listener ! CommandFailed(RegisterIncomingConnection(channel, handler.ref, Nil))
        awaitCond(!channel.isOpen)
      }
    }
  }

  val counter = Iterator.from(0)

  class TestSetup { setup ⇒
    val handler = TestProbe()
    val handlerRef = handler.ref
    val bindCommander = TestProbe()
    val parent = TestProbe()
    val selectorRouter = TestProbe()
    val endpoint = TestUtils.temporaryServerAddress()
    private val parentRef = TestActorRef(new ListenerParent)

    parent.expectMsgType[RegisterServerSocketChannel]

    def bindListener() {
      listener ! Bound
      bindCommander.expectMsg(Bound)
    }

    def attemptConnectionToEndpoint(): Unit = new Socket(endpoint.getHostName, endpoint.getPort)

    def listener = parentRef.underlyingActor.listener

    private class ListenerParent extends Actor {
      val listener = context.actorOf(
        props = Props(new TcpListener(selectorRouter.ref, handler.ref, endpoint, 100, bindCommander.ref,
          Tcp(system).Settings, Nil)),
        name = "test-listener-" + counter.next())
      parent.watch(listener)
      def receive: Receive = {
        case msg ⇒ parent.ref forward msg
      }
      override def supervisorStrategy = SupervisorStrategy.stoppingStrategy
    }
  }

}
