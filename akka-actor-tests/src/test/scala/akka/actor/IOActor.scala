/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.util.ByteString
import akka.util.cps._
import akka.dispatch.Future
import scala.util.continuations._

object IOActorSpec {
  import IO._

  class SimpleEchoServer(host: String, port: Int, ioManager: ActorRef) extends Actor {

    override def preStart = {
      listen(ioManager, host, port)
    }

    def createWorker = Actor.actorOf(Props(new Actor with IO {
      def receiveIO = {
        case NewClient(server) ⇒
          val socket = server.accept()
          loopC {
            val bytes = socket.read()
            socket write bytes
          }
      }
    }).withSupervisor(optionSelf))

    def receive = {
      case msg: NewClient ⇒
        createWorker forward msg
    }

  }

  class SimpleEchoClient(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    lazy val socket: SocketHandle = connect(ioManager, host, port, reader)
    lazy val reader: ActorRef = Actor.actorOf {
      new Actor with IO {
        def receiveIO = {
          case length: Int ⇒
            val bytes = socket.read(length)
            self reply bytes
        }
      }
    }

    def receiveIO = {
      case bytes: ByteString ⇒
        socket write bytes
        reader forward bytes.length
    }
  }

  // Basic Redis-style protocol
  class KVStore(host: String, port: Int, ioManager: ActorRef) extends Actor {

    var kvs: Map[String, ByteString] = Map.empty

    override def preStart = {
      listen(ioManager, host, port)
    }

    def createWorker = Actor.actorOf(Props(new Actor with IO {
      def receiveIO = {
        case NewClient(server) ⇒
          val socket = server.accept()
          loopC {
            val cmd = socket.read(ByteString("\r\n")).utf8String
            val result = matchC(cmd.split(' ')) {
              case Array("SET", key, length) ⇒
                val value = socket read length.toInt
                server.owner ? (('set, key, value)) map ((x: Any) ⇒ ByteString("+OK\r\n"))
              case Array("GET", key) ⇒
                server.owner ? (('get, key)) map {
                  case Some(b: ByteString) ⇒ ByteString("$" + b.length + "\r\n") ++ b
                  case None                ⇒ ByteString("$-1\r\n")
                }
              case Array("GETALL") ⇒
                server.owner ? 'getall map {
                  case m: Map[_, _] ⇒
                    (ByteString("*" + (m.size * 2) + "\r\n") /: m) {
                      case (result, (k: String, v: ByteString)) ⇒
                        val kBytes = ByteString(k)
                        result ++ ByteString("$" + kBytes.length + "\r\n") ++ kBytes ++ ByteString("$" + v.length + "\r\n") ++ v
                    }
                }
            }
            result recover {
              case e ⇒ ByteString("-" + e.getClass.toString + "\r\n")
            } foreach { bytes ⇒
              socket write bytes
            }
          }
      }
    }).withSupervisor(self))

    def receive = {
      case msg: NewClient ⇒ createWorker forward msg
      case ('set, key: String, value: ByteString) ⇒
        kvs += (key -> value)
        self tryReply (())
      case ('get, key: String) ⇒ self tryReply kvs.get(key)
      case 'getall             ⇒ self tryReply kvs
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
        self tryReply readResult

      case ('get, key: String) ⇒
        socket write ByteString("GET " + key + "\r\n")
        self tryReply readResult

      case 'getall ⇒
        socket write ByteString("GETALL\r\n")
        self tryReply readResult
    }

    def readResult = {
      val resultType = socket.read(1).utf8String
      resultType match {
        case "+" ⇒ socket.read(ByteString("\r\n")).utf8String
        case "-" ⇒ sys error socket.read(ByteString("\r\n")).utf8String
        case "$" ⇒
          val length = socket.read(ByteString("\r\n")).utf8String
          socket.read(length.toInt)
        case "*" ⇒
          val count = socket.read(ByteString("\r\n")).utf8String
          var result: Map[String, ByteString] = Map.empty
          repeatC(count.toInt / 2) {
            val k = readBytes
            val v = readBytes
            result += (k.utf8String -> v)
          }
          result
        case _ ⇒ sys error "Unexpected response"
      }
    }

    def readBytes = {
      val resultType = socket.read(1).utf8String
      if (resultType != "$") sys error "Unexpected response"
      val length = socket.read(ByteString("\r\n")).utf8String
      socket.read(length.toInt)
    }
  }

}

class IOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import IOActorSpec._

  "an IO Actor" must {
    "run echo server" in {
      val ioManager = Actor.actorOf(new IOManager(2)) // teeny tiny buffer
      val server = Actor.actorOf(new SimpleEchoServer("localhost", 8064, ioManager))
      val client = Actor.actorOf(new SimpleEchoClient("localhost", 8064, ioManager))
      val f1 = client ? ByteString("Hello World!1")
      val f2 = client ? ByteString("Hello World!2")
      val f3 = client ? ByteString("Hello World!3")
      f1.get must equal(ByteString("Hello World!1"))
      f2.get must equal(ByteString("Hello World!2"))
      f3.get must equal(ByteString("Hello World!3"))
      client.stop
      server.stop
      ioManager.stop
    }

    "run echo server under high load" in {
      val ioManager = Actor.actorOf(new IOManager())
      val server = Actor.actorOf(new SimpleEchoServer("localhost", 8065, ioManager))
      val client = Actor.actorOf(new SimpleEchoClient("localhost", 8065, ioManager))
      val list = List.range(0, 1000)
      val f = Future.traverse(list)(i ⇒ client ? ByteString(i.toString))
      assert(f.get.size === 1000)
      client.stop
      server.stop
      ioManager.stop
    }

    "run echo server under high load with small buffer" in {
      val ioManager = Actor.actorOf(new IOManager(2))
      val server = Actor.actorOf(new SimpleEchoServer("localhost", 8066, ioManager))
      val client = Actor.actorOf(new SimpleEchoClient("localhost", 8066, ioManager))
      val list = List.range(0, 1000)
      val f = Future.traverse(list)(i ⇒ client ? ByteString(i.toString))
      assert(f.get.size === 1000)
      client.stop
      server.stop
      ioManager.stop
    }

    "run key-value store" in {
      val ioManager = Actor.actorOf(new IOManager(2)) // teeny tiny buffer
      val server = Actor.actorOf(new KVStore("localhost", 8067, ioManager))
      val client1 = Actor.actorOf(new KVClient("localhost", 8067, ioManager))
      val client2 = Actor.actorOf(new KVClient("localhost", 8067, ioManager))
      val f1 = client1 ? (('set, "hello", ByteString("World")))
      val f2 = client1 ? (('set, "test", ByteString("No one will read me")))
      val f3 = client1 ? (('get, "hello"))
      f2.await
      val f4 = client2 ? (('set, "test", ByteString("I'm a test!")))
      f4.await
      val f5 = client1 ? (('get, "test"))
      val f6 = client2 ? 'getall
      f1.get must equal("OK")
      f2.get must equal("OK")
      f3.get must equal(ByteString("World"))
      f4.get must equal("OK")
      f5.get must equal(ByteString("I'm a test!"))
      f6.get must equal(Map("hello" -> ByteString("World"), "test" -> ByteString("I'm a test!")))
      client1.stop
      client2.stop
      server.stop
      ioManager.stop
    }
  }

}
