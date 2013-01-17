/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.Socket
import scala.concurrent.duration._
import akka.actor.{ Terminated, SupervisorStrategy, Actor, Props }
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec }
import Tcp._

class TcpListenerSpec extends AkkaSpec("akka.io.tcp.batch-accept-limit = 2") {

  "A TcpListener" must {

    "register its ServerSocketChannel with its selector" in {
      val setup = ListenerSetup(autoBind = false)
      import setup._

      selector.expectMsgType[RegisterServerSocketChannel]
    }

    "let the Bind commander know when binding is completed" in {
      ListenerSetup()
    }

    "accept acceptable connections register them with its parent" in {
      val setup = ListenerSetup()
      import setup._

      new Socket(endpoint.getHostName, endpoint.getPort)
      new Socket(endpoint.getHostName, endpoint.getPort)
      new Socket(endpoint.getHostName, endpoint.getPort)

      listener ! ChannelAcceptable

      val handlerRef = handler.ref
      parent.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ }
      parent.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ }
      parent.expectNoMsg(100.millis)

      listener ! ChannelAcceptable

      parent.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ }
    }

    "react to Unbind commands by replying with Unbound and stopping itself" in {
      val setup = ListenerSetup()
      import setup._

      val unbindCommander = TestProbe()
      unbindCommander.send(listener, Unbind)

      unbindCommander.expectMsg(Unbound)
      parent.expectMsgType[Terminated].actor must be(listener)
    }
  }

  val counter = Iterator.from(0)

  case class ListenerSetup(autoBind: Boolean = true) {
    val selector = TestProbe()
    val handler = TestProbe()
    val bindCommander = TestProbe()
    val parent = TestProbe()
    val endpoint = TemporaryServerAddress("127.0.0.1")
    private val parentRef = TestActorRef(new ListenerParent)

    if (autoBind) {
      listener ! Bound
      bindCommander.expectMsg(Bound)
    }

    def listener = parentRef.underlyingActor.listener
    private class ListenerParent extends Actor {
      val listener = context.actorOf(
        props = Props(new TcpListener(selector.ref, handler.ref, endpoint, 100, bindCommander.ref,
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
