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
  import IO._

  class SimpleEchoServer(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    override def preStart = {
      listen(ioManager, host, port)
    }

    def createWorker = Actor.actorOf(new Actor with IO {
      def receiveIO = {
        case NewClient(server) ⇒
          val socket = server.accept()
          loop { socket write socket.read() }
      }
    })

    def receiveIO = {
      case msg: NewClient ⇒ self startLink createWorker forward msg
    }

  }

  class SimpleEchoClient(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    var socket: SocketHandle = _

    override def preStart: Unit = {
      socket = connect(ioManager, host, port)
    }

    def receiveIO = {
      case bytes: ByteString ⇒
        socket write bytes
        self reply socket.read(bytes.length)
    }
  }

  // Basic Redis-style protocol
  class KVStore(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    var kvs: Map[String, ByteString] = Map.empty

    override def preStart = {
      listen(ioManager, host, port)
    }

    def createWorker = Actor.actorOf(new Actor with IO {
      def receiveIO = {
        case NewClient(server) ⇒
          val socket = server.accept()
          loop {
            val cmd = socket.read(ByteString(" ")).utf8String
            cmd match {
              case "SET" ⇒
                val key = socket.read(ByteString(" ")).utf8String
                val len = socket.read(ByteString("\r\n")).utf8String
                val value = socket read len.toInt
                server.owner ! ('set, key, value)
                socket write ByteString("+OK\r\n")
              case "GET" ⇒
                val key = socket.read(ByteString("\r\n")).utf8String
                server.owner !!! (('get, key)) map { value: Option[ByteString] ⇒
                  value map { bytes ⇒
                    ByteString("$" + bytes.length + "\r\n") ++ bytes
                  } getOrElse ByteString("$-1\r\n")
                } failure {
                  case e ⇒ ByteString("-" + e.getClass.toString + "\r\n")
                } foreach { bytes: ByteString ⇒
                  socket write bytes
                }
            }
          }
      }
    })

    def receiveIO = {
      case msg: NewClient                         ⇒ self startLink createWorker forward msg
      case ('set, key: String, value: ByteString) ⇒ kvs += (key -> value)
      case ('get, key: String)                    ⇒ self reply_? kvs.get(key)
    }

  }

  class KVClient(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    var socket: SocketHandle = _

    override def preStart: Unit = {
      socket = connect(ioManager, host, port)
    }

    def receiveIO = {
      case ('set, key: String, value: ByteString) ⇒
        socket write (ByteString("SET " + key + " " + value.length + "\r\n") ++ value)
        val resultType = socket.read(1).utf8String
        if (resultType != "+") sys.error("Unexpected response")
        val status = socket read ByteString("\r\n")
        self reply status

      case ('get, key: String) ⇒
        socket write ByteString("GET " + key + "\r\n")
        val resultType = socket.read(1).utf8String
        if (resultType != "$") sys.error("Unexpected response")
        val len = socket.read(ByteString("\r\n")).utf8String
        val value = socket read len.toInt
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
      val client1 = Actor.actorOf(new KVClient("localhost", 8064, ioManager)).start
      val client2 = Actor.actorOf(new KVClient("localhost", 8064, ioManager)).start
      val promise1 = client1 !!! (('set, "hello", ByteString("World")))
      val promise2 = client1 !!! (('set, "test", ByteString("No one will read me")))
      val promise3 = client1 !!! (('get, "hello"))
      promise2.await
      val promise4 = client2 !!! (('set, "test", ByteString("I'm a test!")))
      promise4.await
      val promise5 = client1 !!! (('get, "test"))
      (promise1.get: ByteString) must equal(ByteString("OK"))
      (promise2.get: ByteString) must equal(ByteString("OK"))
      (promise3.get: ByteString) must equal(ByteString("World"))
      (promise4.get: ByteString) must equal(ByteString("OK"))
      (promise5.get: ByteString) must equal(ByteString("I'm a test!"))
      client1.stop
      client2.stop
      server.stop
      ioManager.stop
    }

  }

}
