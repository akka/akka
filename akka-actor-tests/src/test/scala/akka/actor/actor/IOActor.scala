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

    var server: Option[IO.Handle] = None
    var clients: Set[IO.Handle] = Set.empty

    override def preStart = {
      server = Some(listen(ioManager, host, port))
    }

    def receive = {
      case IO.NewConnection(handle) ⇒
        println("S: Client connected")
        clients += accept(handle, self)
      case IO.Read(handle, bytes) ⇒
        println("S: Echoing data")
        write(handle, bytes)
      case IO.Closed(handle) ⇒
        println("S: Connection closed")
        clients -= handle
    }

  }

  class SimpleEchoClient(host: String, port: Int, ioManager: ActorRef) extends IOActor {

    sequentialIO = false

    var handle: IO.Handle = _

    override def preStart: Unit = {
      handle = connect(ioManager, host, port)
    }

    def receiveIO = {
      case bytes: ByteString ⇒
        println("C: Sending Request")
        write(handle, bytes)
        self reply read(handle, bytes.length)
        println("C: Got Response")
    }
  }
}

class IOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import IOActorSpec._

  "an IO Actor" must {
    "run" in {
      val ioManager = Actor.actorOf(new IOManager(2)).start // teeny tiny buffer
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
