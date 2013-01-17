/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.{ Socket, InetSocketAddress }
import java.nio.channels.ServerSocketChannel
import scala.concurrent.duration._
import scala.util.Success
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec }
import akka.util.Timeout
import akka.pattern.ask
import Tcp._

class TcpListenerSpec extends AkkaSpec("akka.io.tcp.batch-accept-limit = 2") {
  "A TcpListener" must {
    val manager = TestProbe()
    val selector = TestProbe()
    val handler = TestProbe()
    val handlerRef = handler.ref
    val bindCommander = TestProbe()
    val endpoint = TemporaryServerAddress.get("127.0.0.1")
    val listener = TestActorRef(new TcpListener(manager.ref, selector.ref, handler.ref, endpoint, 100,
      bindCommander.ref, Nil))
    var serverSocketChannel: Option[ServerSocketChannel] = None

    "register its ServerSocketChannel with its selector" in {
      val RegisterServerSocketChannel(channel) = selector.receiveOne(Duration.Zero)
      serverSocketChannel = Some(channel)
    }

    "let the Bind commander know when binding is completed" in {
      listener ! Bound
      bindCommander.expectMsg(Bound)
    }

    "accept two acceptable connections at once and register them with the manager" in {
      new Socket("localhost", endpoint.getPort)
      new Socket("localhost", endpoint.getPort)
      new Socket("localhost", endpoint.getPort)
      listener ! ChannelAcceptable
      val RegisterIncomingConnection(_, `handlerRef`, Nil) = manager.receiveOne(Duration.Zero)
      val RegisterIncomingConnection(_, `handlerRef`, Nil) = manager.receiveOne(Duration.Zero)
    }

    "accept one more connection and register it with the manager" in {
      listener ! ChannelAcceptable
      val RegisterIncomingConnection(_, `handlerRef`, Nil) = manager.receiveOne(Duration.Zero)
    }

    "react to Unbind commands by closing the ServerSocketChannel, replying with Unbound and stopping itself" in {
      implicit val timeout: Timeout = 1 second span
      listener.ask(Unbind).value must equal(Some(Success(Unbound)))
      serverSocketChannel.get.isOpen must equal(false)
      listener.isTerminated must equal(true)
    }
  }

}
