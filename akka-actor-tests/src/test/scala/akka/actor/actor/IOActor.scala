/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.util.ByteString
import akka.dispatch.Promise

object IOActorSpec {

  class SimpleEchoServer(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    var serverToken: Option[IO.Token] = None
    var clientTokens: Set[IO.Token] = Set.empty

    override def preStart = {
      serverToken = Some(listen(ioManager, host, port))
    }

    def receive = {
      case IO.NewConnection(token) ⇒
        println("S: Client connected")
        clientTokens += accept(token, self)
      case IO.Read(token, bytes) ⇒
        println("S: Echoing data")
        write(token, bytes)
      case IO.Closed(token) ⇒
        println("S: Connection closed")
        clientTokens -= token
    }

  }

  class SimpleEchoClient(host: String, port: Int, ioManager: ActorRef) extends IOActor {

    override def preStart: Unit = {
      token = connect(ioManager, host, port)
    }

    def receiveIO = {
      case bytes: ByteString ⇒
        write(bytes)
        val echo = read(bytes.length)
        self reply echo
    }
  }
}

class IOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import IOActorSpec._

  "an IO Actor" must {
    "run" in {
      val ioManager = Actor.actorOf(new IOManager(2)).start
      val server = Actor.actorOf(new SimpleEchoServer("localhost", 8064, ioManager)).start
      val client = Actor.actorOf(new SimpleEchoClient("localhost", 8064, ioManager)).start
      val promise1 = client !!! ByteString("Hello World!1")
      val promise2 = client !!! ByteString("Hello World!2")
      val promise3 = client !!! ByteString("Hello World!3")
      (promise1.get: ByteString) must equal(ByteString("Hello World!1"))
      (promise2.get: ByteString) must equal(ByteString("Hello World!2"))
      (promise3.get: ByteString) must equal(ByteString("Hello World!3"))
      client.stop
      server.stop
      ioManager.stop
    }
  }

}
