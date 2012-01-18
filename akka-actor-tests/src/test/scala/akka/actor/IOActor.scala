/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.BeforeAndAfterEach

import akka.util.ByteString
import akka.util.cps._
import scala.util.continuations._
import akka.testkit._
import akka.dispatch.{ Await, Future }
import akka.pattern.ask

object IOActorSpec {
  import IO._

  class SimpleEchoServer(host: String, port: Int, ioManager: ActorRef, started: TestLatch) extends Actor {

    import context.dispatcher
    implicit val timeout = context.system.settings.ActorTimeout

    override def preStart = {
      listen(ioManager, host, port)
      started.open()
    }

    def createWorker = context.actorOf(Props(new Actor with IO {
      def receiveIO = {
        case NewClient(server) ⇒
          val socket = server.accept()
          loopC {
            val bytes = socket.read()
            socket write bytes
          }
      }
    }))

    def receive = {
      case msg: NewClient ⇒
        createWorker forward msg
    }

  }

  class SimpleEchoClient(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    lazy val socket: SocketHandle = connect(ioManager, host, port)(reader)
    lazy val reader: ActorRef = context.actorOf(Props({
      new Actor with IO {
        def receiveIO = {
          case length: Int ⇒
            val bytes = socket.read(length)
            sender ! bytes
        }
      }
    }))

    def receiveIO = {
      case bytes: ByteString ⇒
        socket write bytes
        reader forward bytes.length
    }
  }

  // Basic Redis-style protocol
  class KVStore(host: String, port: Int, ioManager: ActorRef, started: TestLatch) extends Actor {

    import context.dispatcher
    implicit val timeout = context.system.settings.ActorTimeout

    var kvs: Map[String, ByteString] = Map.empty

    override def preStart = {
      listen(ioManager, host, port)
      started.open()
    }

    def createWorker = context.actorOf(Props(new Actor with IO {
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
    }))

    def receive = {
      case msg: NewClient ⇒ createWorker forward msg
      case ('set, key: String, value: ByteString) ⇒
        kvs += (key -> value)
        sender.tell((), self)
      case ('get, key: String) ⇒ sender.tell(kvs.get(key), self)
      case 'getall             ⇒ sender.tell(kvs, self)
    }

  }

  class KVClient(host: String, port: Int, ioManager: ActorRef) extends Actor with IO {

    import context.dispatcher
    implicit val timeout = context.system.settings.ActorTimeout

    var socket: SocketHandle = _

    override def preStart {
      socket = connect(ioManager, host, port)
    }

    def reply(msg: Any) = sender.tell(msg, self)

    def receiveIO = {
      case ('set, key: String, value: ByteString) ⇒
        socket write (ByteString("SET " + key + " " + value.length + "\r\n") ++ value)
        reply(readResult)

      case ('get, key: String) ⇒
        socket write ByteString("GET " + key + "\r\n")
        reply(readResult)

      case 'getall ⇒
        socket write ByteString("GETALL\r\n")
        reply(readResult)
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class IOActorSpec extends AkkaSpec with BeforeAndAfterEach with DefaultTimeout {
  import IOActorSpec._

  "an IO Actor" must {
    "run echo server" in {
      val started = TestLatch(1)
      val ioManager = system.actorOf(Props(new IOManager(2))) // teeny tiny buffer
      val server = system.actorOf(Props(new SimpleEchoServer("localhost", 8064, ioManager, started)))
      Await.ready(started, timeout.duration)
      val client = system.actorOf(Props(new SimpleEchoClient("localhost", 8064, ioManager)))
      val f1 = client ? ByteString("Hello World!1")
      val f2 = client ? ByteString("Hello World!2")
      val f3 = client ? ByteString("Hello World!3")
      Await.result(f1, timeout.duration) must equal(ByteString("Hello World!1"))
      Await.result(f2, timeout.duration) must equal(ByteString("Hello World!2"))
      Await.result(f3, timeout.duration) must equal(ByteString("Hello World!3"))
      system.stop(client)
      system.stop(server)
      system.stop(ioManager)
    }

    "run echo server under high load" in {
      val started = TestLatch(1)
      val ioManager = system.actorOf(Props(new IOManager()))
      val server = system.actorOf(Props(new SimpleEchoServer("localhost", 8065, ioManager, started)))
      Await.ready(started, timeout.duration)
      val client = system.actorOf(Props(new SimpleEchoClient("localhost", 8065, ioManager)))
      val list = List.range(0, 1000)
      val f = Future.traverse(list)(i ⇒ client ? ByteString(i.toString))
      assert(Await.result(f, timeout.duration).size === 1000)
      system.stop(client)
      system.stop(server)
      system.stop(ioManager)
    }

    "run echo server under high load with small buffer" in {
      val started = TestLatch(1)
      val ioManager = system.actorOf(Props(new IOManager(2)))
      val server = system.actorOf(Props(new SimpleEchoServer("localhost", 8066, ioManager, started)))
      Await.ready(started, timeout.duration)
      val client = system.actorOf(Props(new SimpleEchoClient("localhost", 8066, ioManager)))
      val list = List.range(0, 1000)
      val f = Future.traverse(list)(i ⇒ client ? ByteString(i.toString))
      assert(Await.result(f, timeout.duration).size === 1000)
      system.stop(client)
      system.stop(server)
      system.stop(ioManager)
    }

    "run key-value store" in {
      val started = TestLatch(1)
      val ioManager = system.actorOf(Props(new IOManager(2))) // teeny tiny buffer
      val server = system.actorOf(Props(new KVStore("localhost", 8067, ioManager, started)))
      Await.ready(started, timeout.duration)
      val client1 = system.actorOf(Props(new KVClient("localhost", 8067, ioManager)))
      val client2 = system.actorOf(Props(new KVClient("localhost", 8067, ioManager)))
      val f1 = client1 ? (('set, "hello", ByteString("World")))
      val f2 = client1 ? (('set, "test", ByteString("No one will read me")))
      val f3 = client1 ? (('get, "hello"))
      Await.ready(f2, timeout.duration)
      val f4 = client2 ? (('set, "test", ByteString("I'm a test!")))
      Await.ready(f4, timeout.duration)
      val f5 = client1 ? (('get, "test"))
      val f6 = client2 ? 'getall
      Await.result(f1, timeout.duration) must equal("OK")
      Await.result(f2, timeout.duration) must equal("OK")
      Await.result(f3, timeout.duration) must equal(ByteString("World"))
      Await.result(f4, timeout.duration) must equal("OK")
      Await.result(f5, timeout.duration) must equal(ByteString("I'm a test!"))
      Await.result(f6, timeout.duration) must equal(Map("hello" -> ByteString("World"), "test" -> ByteString("I'm a test!")))
      system.stop(client1)
      system.stop(client2)
      system.stop(server)
      system.stop(ioManager)
    }
  }

}
