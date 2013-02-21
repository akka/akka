/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps
import akka.util.ByteString
import scala.concurrent.{ ExecutionContext, Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.continuations._
import akka.testkit._
import akka.dispatch.MessageDispatcher
import akka.pattern.ask
import java.net.{ Socket, InetSocketAddress, InetAddress, SocketAddress }
import scala.util.Failure
import scala.annotation.tailrec
import akka.AkkaException

object IOActorSpec {

  class SimpleEchoServer(addressPromise: Promise[SocketAddress]) extends Actor {

    val server = IOManager(context.system) listen ("localhost", 0)

    val state = IO.IterateeRef.Map.sync[IO.Handle]()

    def receive = {

      case IO.Listening(`server`, address) ⇒
        addressPromise success address

      case IO.NewClient(`server`) ⇒
        val socket = server.accept()
        state(socket) flatMap (_ ⇒ IO repeat (IO.takeAny map socket.write))

      case IO.Read(socket, bytes) ⇒
        state(socket)(IO Chunk bytes)

      case IO.Closed(socket, cause) ⇒
        state -= socket

    }

    override def postStop {
      server.close()
      state.keySet foreach (_.close())
    }
  }

  class SimpleEchoClient(address: SocketAddress) extends Actor {

    val socket = IOManager(context.system) connect (address)

    val state = IO.IterateeRef.sync()

    def receive = {

      case _: IO.Connected ⇒ //don't care

      case bytes: ByteString ⇒
        val source = sender
        socket write bytes
        state flatMap { _ ⇒ IO take bytes.length map (source ! _) }

      case IO.Read(`socket`, bytes) ⇒
        state(IO Chunk bytes)

      case IO.Closed(`socket`, cause) ⇒
        state(cause)
        cause match {
          case IO.Error(e) ⇒ throw e
          case _           ⇒ throw new RuntimeException("Socket closed")
        }
    }

    override def postStop {
      socket.close()
      state(IO EOF)
    }
  }

  sealed trait KVCommand {
    def bytes: ByteString
  }

  case class KVSet(key: String, value: String) extends KVCommand {
    val bytes = ByteString("SET " + key + " " + value.length + "\r\n" + value + "\r\n")
  }

  case class KVGet(key: String) extends KVCommand {
    val bytes = ByteString("GET " + key + "\r\n")
  }

  case object KVGetAll extends KVCommand {
    val bytes = ByteString("GETALL\r\n")
  }

  // Basic Redis-style protocol
  class KVStore(addressPromise: Promise[SocketAddress]) extends Actor {

    import context.system

    val state = IO.IterateeRef.Map.sync[IO.Handle]()

    var kvs: Map[String, String] = Map.empty

    val server = IOManager(context.system) listen ("localhost", 0)

    val EOL = ByteString("\r\n")

    def receive = {

      case IO.Listening(`server`, address) ⇒
        addressPromise success address

      case IO.NewClient(`server`) ⇒
        val socket = server.accept()
        state(socket) flatMap { _ ⇒
          IO repeat {
            IO takeUntil EOL map (_.utf8String split ' ') flatMap {

              case Array("SET", key, length) ⇒
                for {
                  value ← IO take length.toInt
                  _ ← IO takeUntil EOL
                } yield {
                  kvs += (key -> value.utf8String)
                  ByteString("+OK\r\n")
                }

              case Array("GET", key) ⇒
                IO Iteratee {
                  kvs get key map { value ⇒
                    ByteString("$" + value.length + "\r\n" + value + "\r\n")
                  } getOrElse ByteString("$-1\r\n")
                }

              case Array("GETALL") ⇒
                IO Iteratee {
                  (ByteString("*" + (kvs.size * 2) + "\r\n") /: kvs) {
                    case (result, (k, v)) ⇒
                      val kBytes = ByteString(k)
                      val vBytes = ByteString(v)
                      result ++
                        ByteString("$" + kBytes.length) ++ EOL ++
                        kBytes ++ EOL ++
                        ByteString("$" + vBytes.length) ++ EOL ++
                        vBytes ++ EOL
                  }
                }

            } map (socket write)
          }
        }

      case IO.Read(socket, bytes) ⇒
        state(socket)(IO Chunk bytes)

      case _: IO.Connected ⇒ //don't care

      case IO.Closed(socket, cause) ⇒
        state -= socket

    }

    override def postStop {
      server.close()
      state.keySet foreach (_.close())
    }
  }

  class KVClient(address: SocketAddress) extends Actor {

    val socket = IOManager(context.system) connect (address)

    val state = IO.IterateeRef.sync()

    val EOL = ByteString("\r\n")

    def receive = {
      case cmd: KVCommand ⇒
        val source = sender
        socket write cmd.bytes
        state flatMap { _ ⇒
          readResult map (source !)
        }

      case _: IO.Connected ⇒ //don't care

      case IO.Read(`socket`, bytes) ⇒
        state(IO Chunk bytes)

      case IO.Closed(`socket`, cause) ⇒
        state(cause)
        throw cause match {
          case IO.Error(t) ⇒ t
          case _           ⇒ new RuntimeException("Socket closed")
        }
    }

    override def postStop {
      socket.close()
      state(IO.EOF)
    }

    def readResult: IO.Iteratee[Any] = {
      IO take 1 map (_.utf8String) flatMap {
        case "+" ⇒ IO takeUntil EOL map (msg ⇒ msg.utf8String)
        case "-" ⇒ IO takeUntil EOL flatMap (err ⇒ IO.Failure(new RuntimeException(err.utf8String)))
        case "$" ⇒
          IO takeUntil EOL map (_.utf8String.toInt) flatMap {
            case -1 ⇒ IO Done None
            case length ⇒
              for {
                value ← IO take length
                _ ← IO takeUntil EOL
              } yield Some(value.utf8String)
          }
        case "*" ⇒
          IO takeUntil EOL map (_.utf8String.toInt) flatMap {
            case -1 ⇒ IO Done None
            case length ⇒
              IO.takeList(length)(readResult) flatMap { list ⇒
                ((Right(Map()): Either[String, Map[String, String]]) /: list.grouped(2)) {
                  case (Right(m), List(Some(k: String), Some(v: String))) ⇒ Right(m + (k -> v))
                  case (Right(_), _) ⇒ Left("Unexpected Response")
                  case (left, _) ⇒ left
                } fold (msg ⇒ IO.Failure(new RuntimeException(msg)), IO Done _)
              }
          }
        case _ ⇒ IO.Failure(new RuntimeException("Unexpected Response"))
      }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class IOActorSpec extends AkkaSpec with DefaultTimeout {
  import IOActorSpec._

  /**
   * Retries the future until a result is returned or until one of the limits are hit. If no
   * limits are provided the future will be retried indefinitely until a result is returned.
   *
   * @param count number of retries
   * @param timeout duration to retry within
   * @param delay duration to wait before retrying
   * @param filter determines which exceptions should be retried
   * @return a future containing the result or the last exception before a limit was hit.
   */
  def retry[T](count: Option[Int] = None,
               timeout: Option[FiniteDuration] = None,
               delay: Option[FiniteDuration] = Some(100 millis),
               filter: Option[Throwable ⇒ Boolean] = None)(future: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {

    val promise = Promise[T]()

    val timer: Option[Deadline] = timeout match {
      case Some(duration) ⇒ Some(duration fromNow)
      case None           ⇒ None
    }

    def check(n: Int, e: Throwable): Boolean =
      (count.isEmpty || (n < count.get)) && (timer.isEmpty || timer.get.hasTimeLeft()) && (filter.isEmpty || filter.get(e))

    def run(n: Int) {
      future onComplete {
        case Failure(e) if check(n, e) ⇒
          if (delay.isDefined) {
            executor match {
              case m: MessageDispatcher ⇒ m.prerequisites.scheduler.scheduleOnce(delay.get)(run(n + 1))
              case _                    ⇒ // Thread.sleep, ignore, or other?
            }
          } else run(n + 1)
        case v ⇒ promise complete v
      }
    }

    run(0)

    promise.future
  }

  "an IO Actor" must {
    import system.dispatcher
    "run echo server" in {
      filterException[java.net.ConnectException] {
        val addressPromise = Promise[SocketAddress]()
        val server = system.actorOf(Props(new SimpleEchoServer(addressPromise)))
        val address = Await.result(addressPromise.future, TestLatch.DefaultTimeout)
        val client = system.actorOf(Props(new SimpleEchoClient(address)))
        val f1 = retry() { client ? ByteString("Hello World!1") }
        val f2 = retry() { client ? ByteString("Hello World!2") }
        val f3 = retry() { client ? ByteString("Hello World!3") }
        Await.result(f1, TestLatch.DefaultTimeout) must equal(ByteString("Hello World!1"))
        Await.result(f2, TestLatch.DefaultTimeout) must equal(ByteString("Hello World!2"))
        Await.result(f3, TestLatch.DefaultTimeout) must equal(ByteString("Hello World!3"))
        system.stop(client)
        system.stop(server)
      }
    }

    "run echo server under high load" in {
      filterException[java.net.ConnectException] {
        val addressPromise = Promise[SocketAddress]()
        val server = system.actorOf(Props(new SimpleEchoServer(addressPromise)))
        val address = Await.result(addressPromise.future, TestLatch.DefaultTimeout)
        val client = system.actorOf(Props(new SimpleEchoClient(address)))
        val list = List.range(0, 100)
        val f = Future.traverse(list)(i ⇒ retry() { client ? ByteString(i.toString) })
        assert(Await.result(f, TestLatch.DefaultTimeout).size === 100)
        system.stop(client)
        system.stop(server)
      }
    }

    "run key-value store" in {
      filterException[java.net.ConnectException] {
        val addressPromise = Promise[SocketAddress]()
        val server = system.actorOf(Props(new KVStore(addressPromise)))
        val address = Await.result(addressPromise.future, TestLatch.DefaultTimeout)
        val client1 = system.actorOf(Props(new KVClient(address)))
        val client2 = system.actorOf(Props(new KVClient(address)))
        val f1 = retry() { client1 ? KVSet("hello", "World") }
        val f2 = retry() { client1 ? KVSet("test", "No one will read me") }
        val f3 = f1 flatMap { _ ⇒ retry() { client1 ? KVGet("hello") } }
        val f4 = f2 flatMap { _ ⇒ retry() { client2 ? KVSet("test", "I'm a test!") } }
        val f5 = f4 flatMap { _ ⇒ retry() { client1 ? KVGet("test") } }
        val f6 = Future.sequence(List(f3, f5)) flatMap { _ ⇒ retry() { client2 ? KVGetAll } }
        Await.result(f1, TestLatch.DefaultTimeout) must equal("OK")
        Await.result(f2, TestLatch.DefaultTimeout) must equal("OK")
        Await.result(f3, TestLatch.DefaultTimeout) must equal(Some("World"))
        Await.result(f4, TestLatch.DefaultTimeout) must equal("OK")
        Await.result(f5, TestLatch.DefaultTimeout) must equal(Some("I'm a test!"))
        Await.result(f6, TestLatch.DefaultTimeout) must equal(Map("hello" -> "World", "test" -> "I'm a test!"))
        system.stop(client1)
        system.stop(client2)
        system.stop(server)
      }
    }

    "takeUntil must fail on EOF before predicate when used with repeat" in {
      val CRLF = ByteString("\r\n")
      val dest = new InetSocketAddress(InetAddress.getLocalHost.getHostAddress, { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() })

      val a = system.actorOf(Props(new Actor {
        val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)

        override def preStart {
          IOManager(context.system) listen dest
        }

        def receive = {
          case _: IO.Listening ⇒ testActor ! "Wejkipejki"
          case IO.NewClient(server) ⇒
            val socket = server.accept()
            state(socket) flatMap (_ ⇒ IO.repeat(for (input ← IO.takeUntil(CRLF)) yield testActor ! input.utf8String))

          case IO.Read(socket, bytes) ⇒
            state(socket)(IO Chunk bytes)

          case IO.Closed(socket, cause) ⇒
            state(socket)(IO EOF)
            state -= socket
            testActor ! "eof"
        }
      }))
      expectMsg("Wejkipejki")
      val s = new Socket(dest.getAddress, dest.getPort)
      try {
        val expectedReceive = Seq("ole", "dole", "doff", "kinke", "lane", "koff", "ole", "dole", "dinke", "dane", "ole", "dole")
        val expectedSend = expectedReceive ++ Seq("doff")
        val out = s.getOutputStream
        out.write(expectedSend.mkString(CRLF.utf8String).getBytes("UTF-8"))
        out.flush()
        for (word ← expectedReceive) expectMsg(word)
        s.close()
        expectMsg("eof")
      } finally {
        if (!s.isClosed) s.close()
      }
    }

    "fail when listening on an invalid address" in {
      implicit val self = testActor
      val address = new InetSocketAddress("irate.elephant", 9999)
      IOManager(system).listen(address)
      expectMsgType[Status.Failure](1 seconds)
    }

    "fail when listening on a privileged port" in {
      implicit val self = testActor
      val address = new InetSocketAddress("localhost", 80) // Assumes test not run as root
      IOManager(system).listen(address)
      expectMsgType[Status.Failure](1 seconds)
    }

    "fail when connecting to an invalid address" in {
      implicit val self = testActor
      val address = new InetSocketAddress("irate.elephant", 80)
      IOManager(system).connect(address)
      expectMsgType[Status.Failure](1 seconds)
    }

    "fail when binding to already bound port and report port in failure" in {
      implicit val self = testActor
      IOManager(system).listen(new InetSocketAddress("localhost", 0))
      val boundTo = expectMsgType[IO.Listening].address.asInstanceOf[InetSocketAddress]
      IOManager(system).listen(boundTo)
      val exc = expectMsgType[Status.Failure].cause
      exc.getClass must be(classOf[AkkaException])
      exc.getMessage must include("Address already in use")
      exc.getMessage must include(boundTo.getPort.toString)
    }

  }

}
