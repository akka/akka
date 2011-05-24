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

  class SimpleEchoClient(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    var requests: Map[IO.Token, (ByteString, Int, Promise[Any])] = Map.empty

    def receive = {
      case bytes: ByteString ⇒
        println("C: New request")
        val token = connect(ioManager, host, port)
        write(token, bytes)
        requests += (token -> (ByteString.empty, bytes.length, self.senderFuture.get))
      case IO.Connected(token) ⇒
        println("C: Connected to server")
      case IO.Read(token, bytes) ⇒
        val (result, size, promise) = requests(token)
        if (bytes.length + result.length == size) {
          println("C: Received complete result")
          requests -= token
          close(token)
          promise complete Right(result ++ bytes)
        } else {
          println("C: Received partial result")
          requests += (token -> (result ++ bytes, size, promise))
        }
      case IO.Closed(token) ⇒
        println("C: Connection closed")
    }
  }
}

class IOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import IOActorSpec._

  "an IO Actor" must {
    "run" in {
      val ioManager = Actor.actorOf(new IOManager()).start
      val server = Actor.actorOf(new SimpleEchoServer("localhost", 8064, ioManager)).start
      val client = Actor.actorOf(new SimpleEchoClient("localhost", 8064, ioManager)).start
      val promise = client !!! ByteString("Hello World!")
      (promise.get: ByteString) must equal(ByteString("Hello World!"))
      client.stop
      server.stop
      ioManager.stop
    }
  }

}
