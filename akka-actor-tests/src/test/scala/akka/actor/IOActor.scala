/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.util.{ ByteString, Duration, Timer }
import akka.util.duration._
import scala.util.continuations._
import akka.testkit._
import akka.dispatch.{ Await, Future, Promise, ExecutionContext, MessageDispatcher }

object IOActorSpec {

  class SimpleEchoServer(host: String, port: Int) extends Actor {

    IOManager(context.system) listen (host, port)

    val state = IO.IterateeRef.Map.sync[IO.Handle]()

    def receive = {

      case IO.NewClient(server) ⇒
        val socket = server.accept()
        state(socket) flatMap (_ ⇒ IO repeat (IO.takeAny map socket.write))

      case IO.Read(socket, bytes) ⇒
        state(socket)(IO Chunk bytes)

      case IO.Closed(socket, cause) ⇒
        state -= socket

    }

  }

  class SimpleEchoClient(host: String, port: Int) extends Actor {

    var socket = IOManager(context.system) connect (host, port)

    val state = IO.IterateeRef.sync()

    def receive = {

      case bytes: ByteString ⇒
        val source = sender
        socket write bytes
        state flatMap { _ ⇒
          IO take bytes.length map (source ! _) recover {
            case e ⇒ source ! Status.Failure(e)
          }
        }

      case IO.Read(_, bytes) ⇒
        state(IO Chunk bytes)

      case IO.Connected(_) ⇒

      case IO.Closed(_, cause) ⇒
        state(IO EOF cause)
        socket = IOManager(context.system) connect (host, port)

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
  class KVStore(host: String, port: Int, started: TestLatch) extends Actor {

    import context.system

    val state = IO.IterateeRef.Map.sync[IO.Handle]()

    var kvs: Map[String, String] = Map.empty

    IOManager(context.system) listen (host, port)

    started.open

    val EOL = ByteString("\r\n")

    def receive = {

      case IO.NewClient(server) ⇒
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

      case IO.Closed(socket, cause) ⇒
        state -= socket

    }

  }

  class KVClient(host: String, port: Int) extends Actor {

    val socket = IOManager(context.system) connect (host, port)

    val state = IO.IterateeRef.sync()

    val EOL = ByteString("\r\n")

    def receive = {
      case cmd: KVCommand ⇒
        val source = sender
        socket write cmd.bytes
        for {
          _ ← state
          result ← readResult
        } yield result.fold(err ⇒ source ! Status.Failure(new RuntimeException(err)), source !)

      case IO.Read(socket, bytes) ⇒
        state(IO Chunk bytes)

      case IO.Connected(socket)     ⇒

      case IO.Closed(socket, cause) ⇒

    }

    def readResult: IO.Iteratee[Either[String, Any]] = {
      IO take 1 map (_.utf8String) flatMap {
        case "+" ⇒ IO takeUntil EOL map (msg ⇒ Right(msg.utf8String))
        case "-" ⇒ IO takeUntil EOL map (err ⇒ Left(err.utf8String))
        case "$" ⇒
          IO takeUntil EOL map (_.utf8String.toInt) flatMap {
            case -1 ⇒ IO Iteratee Right(None)
            case length ⇒
              for {
                value ← IO take length
                _ ← IO takeUntil EOL
              } yield Right(Some(value.utf8String))
          }
        case "*" ⇒
          IO takeUntil EOL map (_.utf8String.toInt) flatMap {
            case -1 ⇒ IO Iteratee Right(None)
            case length ⇒
              IO.takeList(length)(readResult) map { list ⇒
                ((Right(Map()): Either[String, Map[String, String]]) /: list.grouped(2)) {
                  case (Right(m), List(Right(Some(k: String)), Right(Some(v: String)))) ⇒ Right(m + (k -> v))
                  case (Right(_), _) ⇒ Left("Unexpected Response")
                  case (left, _) ⇒ left
                }
              }
          }
        case _ ⇒ IO Iteratee Left("Unexpected Response")
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
  def retry[T](count: Option[Int] = None, timeout: Option[Duration] = None, delay: Option[Duration] = None, filter: Option[Throwable ⇒ Boolean] = None)(future: ⇒ Future[T])(implicit executor: ExecutionContext): Future[T] = {

    val promise = Promise[T]()(executor)

    val timer = timeout match {
      case Some(duration) ⇒ Some(Timer(duration))
      case None           ⇒ None
    }

    def check(n: Int, e: Throwable): Boolean =
      (count.isEmpty || (n < count.get)) && (timer.isEmpty || timer.get.isTicking) && (filter.isEmpty || filter.get(e))

    def run(n: Int) {
      future onComplete {
        case Left(e) if check(n, e) ⇒
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

    promise
  }

  "an IO Actor" must {
    "run echo server" in {
      val client = system.actorOf(Props(new SimpleEchoClient("localhost", 8064)))
      val f1 = retry() { client ? ByteString("Hello World!1") }
      val f2 = retry() { client ? ByteString("Hello World!2") }
      val f3 = retry() { client ? ByteString("Hello World!3") }
      val server = system.actorOf(Props(new SimpleEchoServer("localhost", 8064)))
      Await.result(f1, TestLatch.DefaultTimeout) must equal(ByteString("Hello World!1"))
      Await.result(f2, TestLatch.DefaultTimeout) must equal(ByteString("Hello World!2"))
      Await.result(f3, TestLatch.DefaultTimeout) must equal(ByteString("Hello World!3"))
    }

    "run echo server under high load" in {
      val client = system.actorOf(Props(new SimpleEchoClient("localhost", 8065)))
      val list = List.range(0, 1000)
      val f = Future.traverse(list)(i ⇒ retry() { client ? ByteString(i.toString) })
      val server = system.actorOf(Props(new SimpleEchoServer("localhost", 8065)))
      assert(Await.result(f, TestLatch.DefaultTimeout).size === 1000)
    }

    // Not currently configurable at runtime
    /*
    "run echo server under high load with small buffer" in {
      val started = TestLatch(1)
      val ioManager = actorOf(new IOManager(2))
      val server = actorOf(new SimpleEchoServer("localhost", 8066, ioManager, started))
      started.await
      val client = actorOf(new SimpleEchoClient("localhost", 8066, ioManager))
      val list = List.range(0, 1000)
      val f = Future.traverse(list)(i ⇒ client ? ByteString(i.toString))
      assert(Await.result(f, timeout.duration).size === 1000)
      system.stop(client)
      system.stop(server)
      system.stop(ioManager)
    }
    */

    "run key-value store" in {
      val started = TestLatch(1)
      val server = system.actorOf(Props(new KVStore("localhost", 8067, started)))
      Await.ready(started, TestLatch.DefaultTimeout)
      val client1 = system.actorOf(Props(new KVClient("localhost", 8067)))
      val client2 = system.actorOf(Props(new KVClient("localhost", 8067)))
      val f1 = client1 ? KVSet("hello", "World")
      val f2 = client1 ? KVSet("test", "No one will read me")
      val f3 = client1 ? KVGet("hello")
      Await.ready(f2, TestLatch.DefaultTimeout)
      val f4 = client2 ? KVSet("test", "I'm a test!")
      Await.ready(f4, TestLatch.DefaultTimeout)
      val f5 = client1 ? KVGet("test")
      val f6 = client2 ? KVGetAll
      Await.result(f1, TestLatch.DefaultTimeout) must equal("OK")
      Await.result(f2, TestLatch.DefaultTimeout) must equal("OK")
      Await.result(f3, TestLatch.DefaultTimeout) must equal(Some("World"))
      Await.result(f4, TestLatch.DefaultTimeout) must equal("OK")
      Await.result(f5, TestLatch.DefaultTimeout) must equal(Some("I'm a test!"))
      Await.result(f6, TestLatch.DefaultTimeout) must equal(Map("hello" -> "World", "test" -> "I'm a test!"))
    }
  }

}
