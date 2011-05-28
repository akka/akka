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

  class SimpleEchoServer(host: String, port: Int, ioManager: ActorRef) extends IOActor {

    sequentialIO = false
    idleWakeup = true

    override def preStart = {
      listen(ioManager, host, port)
    }

    def receiveIO = {
      case IO.NewConnection(handle) ⇒ accept(handle)
      case IO.WakeUp(handle)        ⇒ write(handle, read(handle))
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
        write(handle, bytes)
        self reply read(handle, bytes.length)
    }
  }

  // Basic Redis-style protocol
  class KVStore(host: String, port: Int, ioManager: ActorRef) extends IOActor {

    sequentialIO = false
    idleWakeup = true

    var kvs: Map[String, ByteString] = Map.empty

    override def preStart = {
      listen(ioManager, host, port)
    }

    def receiveIO = {
      case IO.NewConnection(handle) ⇒
        accept(handle)
      case IO.WakeUp(handle) ⇒
        val cmd = read(handle, ByteString(" ")).utf8String.trim
        cmd match {
          case "SET" ⇒
            val key = read(handle, ByteString(" ")).utf8String.trim
            val len = read(handle, ByteString("\r\n")).utf8String.trim
            val value = read(handle, len.toInt)
            kvs += (key -> value)
            write(handle, ByteString("+OK\r\n"))
          case "GET" ⇒
            val key = read(handle, ByteString("\r\n")).utf8String.trim
            write(handle, kvs.get(key).map(v ⇒ ByteString("$" + v.length + "\r\n") ++ v).getOrElse(ByteString("$-1\r\n")))
        }
    }

  }

  class KVClient(host: String, port: Int, ioManager: ActorRef) extends IOActor {

    // FIXME: should prioritize reads from first message
    // sequentialIO = false

    var handle: IO.Handle = _

    override def preStart: Unit = {
      handle = connect(ioManager, host, port)
    }

    def receiveIO = {
      case ('set, key: String, value: ByteString) ⇒
        write(handle, ByteString("SET " + key + " " + value.length + "\r\n") ++ value)
        val resultType = read(handle, 1).utf8String
        if (resultType != "+") sys.error("Unexpected response")
        val status = read(handle, ByteString("\r\n"))
        self reply status.take(status.length - 2)

      case ('get, key: String) ⇒
        write(handle, ByteString("GET " + key + "\r\n"))
        val resultType = read(handle, 1).utf8String
        if (resultType != "$") sys.error("Unexpected response")
        val len = read(handle, ByteString("\r\n")).utf8String.trim
        val value = read(handle, len.toInt)
        self reply value
    }
  }

}

class IOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import IOActorSpec._

  "an IO Actor" must {
    "run echo server" in {
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

    "run key-value store" in {
      val ioManager = Actor.actorOf(new IOManager(2)).start // teeny tiny buffer
      val server = Actor.actorOf(new KVStore("localhost", 8064, ioManager)).start
      val client = Actor.actorOf(new KVClient("localhost", 8064, ioManager)).start
      val promise1 = client !!! (('set, "hello", ByteString("World")))
      val promise2 = client !!! (('set, "test", ByteString("No one will read me")))
      val promise3 = client !!! (('get, "hello"))
      val promise4 = client !!! (('set, "test", ByteString("I'm a test!")))
      val promise5 = client !!! (('get, "test"))
      (promise1.get: ByteString) must equal(ByteString("OK"))
      (promise2.get: ByteString) must equal(ByteString("OK"))
      (promise3.get: ByteString) must equal(ByteString("World"))
      (promise4.get: ByteString) must equal(ByteString("OK"))
      (promise5.get: ByteString) must equal(ByteString("I'm a test!"))
      client.stop
      server.stop
      ioManager.stop
    }

  }

}
