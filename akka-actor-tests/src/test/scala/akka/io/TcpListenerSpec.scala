/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.Socket
import java.nio.channels.{ SelectableChannel, SocketChannel }
import java.nio.channels.SelectionKey.OP_ACCEPT
import scala.concurrent.duration._
import akka.actor._
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec, EventFilter }
import akka.io.TcpListener.{ RegisterIncoming, FailedRegisterIncoming }
import akka.io.SelectionHandler._
import akka.TestUtils
import Tcp._

class TcpListenerSpec extends AkkaSpec("akka.io.tcp.batch-accept-limit = 2") {

  "A TcpListener" must {

    "register its ServerSocketChannel with its selector" in new TestSetup

    "let the Bind commander know when binding is completed" in new TestSetup {
      listener ! new ChannelRegistration {
        def disableInterest(op: Int) = ()
        def enableInterest(op: Int) = ()
      }
      bindCommander.expectMsgType[Bound]
    }

    "accept acceptable connections and register them with its parent" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()
      attemptConnectionToEndpoint()
      attemptConnectionToEndpoint()

      // since the batch-accept-limit is 2 we must only receive 2 accepted connections
      listener ! ChannelAcceptable

      expectWorkerForCommand
      expectWorkerForCommand
      selectorRouter.expectNoMsg(100.millis)
      interestCallReceiver.expectMsg(OP_ACCEPT)

      // and pick up the last remaining connection on the next ChannelAcceptable
      listener ! ChannelAcceptable
      expectWorkerForCommand
    }

    "continue to accept connections after a previous accept" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()
      listener ! ChannelAcceptable
      expectWorkerForCommand
      selectorRouter.expectNoMsg(100.millis)
      interestCallReceiver.expectMsg(OP_ACCEPT)

      attemptConnectionToEndpoint()
      listener ! ChannelAcceptable
      expectWorkerForCommand
      selectorRouter.expectNoMsg(100.millis)
      interestCallReceiver.expectMsg(OP_ACCEPT)
    }

    "react to Unbind commands by replying with Unbound and stopping itself" in new TestSetup {
      bindListener()

      val unbindCommander = TestProbe()
      unbindCommander.send(listener, Unbind)

      unbindCommander.expectMsg(Unbound)
      parent.expectTerminated(listener)
    }

    "drop an incoming connection if it cannot be registered with a selector" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()

      listener ! ChannelAcceptable
      val channel = expectWorkerForCommand

      EventFilter.warning(pattern = "selector capacity limit", occurrences = 1) intercept {
        listener ! FailedRegisterIncoming(channel)
        awaitCond(!channel.isOpen)
      }
    }
  }

  val counter = Iterator.from(0)

  class TestSetup {
    val handler = TestProbe()
    val handlerRef = handler.ref
    val bindCommander = TestProbe()
    val parent = TestProbe()
    val selectorRouter = TestProbe()
    val endpoint = TestUtils.temporaryServerAddress()

    var registerCallReceiver = TestProbe()
    var interestCallReceiver = TestProbe()

    private val parentRef = TestActorRef(new ListenerParent)

    registerCallReceiver.expectMsg(OP_ACCEPT)

    def bindListener() {
      listener ! new ChannelRegistration {
        def enableInterest(op: Int): Unit = interestCallReceiver.ref ! op
        def disableInterest(op: Int): Unit = interestCallReceiver.ref ! -op
      }
      bindCommander.expectMsgType[Bound]
    }

    def attemptConnectionToEndpoint(): Unit = new Socket(endpoint.getHostName, endpoint.getPort)

    def listener = parentRef.underlyingActor.listener

    def expectWorkerForCommand: SocketChannel =
      selectorRouter.expectMsgPF() {
        case WorkerForCommand(RegisterIncoming(chan), commander, _) ⇒
          chan.isOpen must be(true)
          commander must be === listener
          chan
      }

    private class ListenerParent extends Actor with ChannelRegistry {
      val listener = context.actorOf(
        props = Props(classOf[TcpListener], selectorRouter.ref, Tcp(system), this, bindCommander.ref,
          Bind(handler.ref, endpoint, 100, Nil)),
        name = "test-listener-" + counter.next())
      parent.watch(listener)
      def receive: Receive = {
        case msg ⇒ parent.ref forward msg
      }
      override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

      def register(channel: SelectableChannel, initialOps: Int)(implicit channelActor: ActorRef): Unit =
        registerCallReceiver.ref.tell(initialOps, channelActor)
    }
  }

}
