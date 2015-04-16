/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, Props}
import akka.io.{IO, Udp}
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.{AkkaSpec, TestUtils}
import akka.testkit.TestProbe
import akka.util.ByteString

class SimpleUdpSinkSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

  "A Flow with Sink.simpleUdp" must {
    val addr = TestUtils.temporaryServerAddress(udp = true)
    val serverProbe = createUdpServer(addr)

    val Hello = ByteString("Hello")
    val World = ByteString("World")
    val ExclamationMark = ByteString("!")
    val words = Source(List(Hello, World, ExclamationMark))

    "send all received ByteStrings using UDP" in {
      words.runWith(Sink.simpleUdp(addr.getAddress.getHostAddress, addr.getPort))

      serverProbe.expectMsg(Hello)
      serverProbe.expectMsg(World)
      serverProbe.expectMsg(ExclamationMark)
    }

  }

  def createUdpServer(addr: InetSocketAddress) = {
    val p = TestProbe()
    system.actorOf(Props(new ServerActor(addr, p)).withDispatcher("akka.test.stream-dispatcher"))
    p.expectMsg("init-done")
    p
  }

  class ServerActor(addr: InetSocketAddress, p: TestProbe) extends Actor {
    IO(Udp)(context.system) ! Udp.Bind(self, addr)

    def receive = {
      case Udp.Bound(local) ⇒
        p.ref ! "init-done"
        context.become(ready(sender()))
    }

    def ready(socket: ActorRef): Receive = {
      case Udp.Received(data, remote) ⇒ p.ref ! data
      case Udp.Unbind                 ⇒ socket ! Udp.Unbind
      case Udp.Unbound                ⇒ context.stop(self)
    }
  }

}
