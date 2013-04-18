/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io

//#imports
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
//#imports

import akka.testkit.AkkaSpec
import scala.concurrent.duration._

class DemoActor extends Actor {
  //#manager
  import akka.io.{ IO, Tcp }
  import context.system // implicitly used by IO(Tcp)

  val manager = IO(Tcp)
  //#manager

  def receive = Actor.emptyBehavior
}

//#server
object Server {
  def apply(manager: ActorRef) = Props(classOf[Server], manager)
}

class Server(manager: ActorRef) extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 0))

  def receive = {
    case b @ Bound(localAddress) ⇒ manager ! b

    case CommandFailed(_: Bind)  ⇒ context stop self

    case c @ Connected(remote, local) ⇒
      manager ! c
      val handler = context.actorOf(Props[SimplisticHandler])
      val connection = sender
      connection ! Register(handler)
  }

}
//#server

//#simplistic-handler
class SimplisticHandler extends Actor {
  import Tcp._
  def receive = {
    case Received(data) ⇒ sender ! Write(data)
    case PeerClosed     ⇒ context stop self
  }
}
//#simplistic-handler

//#client
object Client {
  def apply(remote: InetSocketAddress, replies: ActorRef) =
    Props(classOf[Client], remote, replies)
}

class Client(remote: InetSocketAddress, listener: ActorRef) extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  def receive = {
    case CommandFailed(_: Connect) ⇒
      listener ! "failed"
      context stop self

    case c @ Connected(remote, local) ⇒
      listener ! c
      val connection = sender
      connection ! Register(self)
      context become {
        case data: ByteString        ⇒ connection ! Write(data)
        case CommandFailed(w: Write) ⇒ // O/S buffer was full
        case Received(data)          ⇒ listener ! data
        case "close"                 ⇒ connection ! Close
        case _: ConnectionClosed     ⇒ context stop self
      }
  }
}
//#client

class IODocSpec extends AkkaSpec {

  "demonstrate connect" in {
    val server = system.actorOf(Server(testActor), "server1")
    val listen = expectMsgType[Tcp.Bound].localAddress
    val client = system.actorOf(Client(listen, testActor), "client1")

    val c1, c2 = expectMsgType[Tcp.Connected]
    c1.localAddress must be(c2.remoteAddress)
    c2.localAddress must be(c1.remoteAddress)

    client ! ByteString("hello")
    expectMsgType[ByteString].utf8String must be("hello")

    watch(client)
    client ! "close"
    expectTerminated(client, 1.second)
  }

}
