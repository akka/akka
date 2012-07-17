package akka.dispatch

import language.postfixOps

import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.Checkers
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen._
import akka.actor._
import akka.testkit.{ EventFilter, filterEvents, filterException, AkkaSpec, DefaultTimeout, TestLatch }
import scala.concurrent.{ Await, Future, Promise }
import scala.util.control.NonFatal
import scala.concurrent.util.duration._
import scala.concurrent.ExecutionContext
import org.scalatest.junit.JUnitSuite
import scala.runtime.NonLocalReturnControl
import akka.pattern.ask
import java.lang.{ IllegalStateException, ArithmeticException }
import java.util.concurrent._

object FutureSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒ sender ! "World"
      case "Failure" ⇒
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
      case "NoReply" ⇒
    }
  }

  class TestDelayActor(await: TestLatch) extends Actor {
    def receive = {
      case "Hello"   ⇒ Await.ready(await, TestLatch.DefaultTimeout); sender ! "World"
      case "NoReply" ⇒ Await.ready(await, TestLatch.DefaultTimeout)
      case "Failure" ⇒
        Await.ready(await, TestLatch.DefaultTimeout)
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
    }
  }
}

class JavaFutureSpec extends JavaFutureTests with JUnitSuite

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FutureSpec extends AkkaSpec with Checkers with BeforeAndAfterAll with DefaultTimeout {
  import FutureSpec._
  implicit val ec = system.dispatcher
  "A Promise" when {
    "never completed" must {
      behave like emptyFuture(_(Promise().future))
      "return supplied value on timeout" in {
        val failure = Promise.failed[String](new RuntimeException("br0ken")).future
        val otherFailure = Promise.failed[String](new RuntimeException("last")).future
        val empty = Promise[String]().future
        val timedOut = Promise.successful[String]("Timedout").future

        Await.result(failure fallbackTo timedOut, timeout.duration) must be("Timedout")
        Await.result(timedOut fallbackTo empty, timeout.duration) must be("Timedout")
        Await.result(failure fallbackTo failure fallbackTo timedOut, timeout.duration) must be("Timedout")
        intercept[RuntimeException] {
          Await.result(failure fallbackTo otherFailure, timeout.duration)
        }.getMessage must be("last")
      }
    }
    "completed with a result" must {
      val result = "test value"
      val future = Promise[String]().complete(Right(result)).future
      behave like futureWithResult(_(future, result))
    }
    "completed with an exception" must {
      val message = "Expected Exception"
      val future = Promise[String]().complete(Left(new RuntimeException(message))).future
      behave like futureWithException[RuntimeException](_(future, message))
    }
    "completed with an InterruptedException" must {
      val message = "Boxed InterruptedException"
      val future = Promise[String]().complete(Left(new InterruptedException(message))).future
      behave like futureWithException[RuntimeException](_(future, message))
    }
    "completed with a NonLocalReturnControl" must {
      val result = "test value"
      val future = Promise[String]().complete(Left(new NonLocalReturnControl[String]("test", result))).future
      behave like futureWithResult(_(future, result))
    }

    "have different ECs" in {
      def namedCtx(n: String) =
        ExecutionContext.fromExecutorService(
          Executors.newSingleThreadExecutor(new ThreadFactory { def newThread(r: Runnable) = new Thread(r, n) }))

      val A = namedCtx("A")
      val B = namedCtx("B")

      // create a promise with ctx A
      val p = Promise[String]()

      // I would expect that any callback from p
      // is executed in the context of p
      val result = {
        implicit val ec = A
        p.future map { _ + Thread.currentThread().getName() }
      }

      p.completeWith(Future { "Hi " }(B))
      try {
        Await.result(result, timeout.duration) must be === "Hi A"
      } finally {
        A.shutdown()
        B.shutdown()
      }
    }
  }

  "A Future" when {

    "awaiting a result" that {
      "is not completed" must {
        behave like emptyFuture { test ⇒
          val latch = new TestLatch
          val result = "test value"
          val future = Future {
            Await.ready(latch, TestLatch.DefaultTimeout)
            result
          }
          test(future)
          latch.open()
          Await.ready(future, timeout.duration)
        }
      }
      "is completed" must {
        behave like futureWithResult { test ⇒
          val latch = new TestLatch
          val result = "test value"
          val future = Future {
            Await.ready(latch, TestLatch.DefaultTimeout)
            result
          }
          latch.open()
          Await.ready(future, timeout.duration)
          test(future, result)
        }
      }
      "has actions applied" must {
        "pass checks" in {
          filterException[ArithmeticException] {
            check({ (future: Future[Int], actions: List[FutureAction]) ⇒
              def wrap[T](f: Future[T]): Either[Throwable, T] = try Await.ready(f, timeout.duration).value.get catch { case t ⇒ println(f.getClass + " - " + t.getClass + ": " + t.getMessage + ""); f.value.get }
              val result = (future /: actions)(_ /: _)
              val expected = (wrap(future) /: actions)(_ /: _)
              ((wrap(result), expected) match {
                case (Right(a), Right(b)) ⇒ a == b
                case (Left(a), Left(b)) if a.toString == b.toString ⇒ true
                case (Left(a), Left(b)) if a.getStackTrace.isEmpty || b.getStackTrace.isEmpty ⇒ a.getClass.toString == b.getClass.toString
                case _ ⇒ false
              }) :| result.value.get.toString + " is expected to be " + expected.toString
            }, minSuccessful(10000), workers(4))
          }
        }
      }
    }

    "from an Actor" that {
      "returns a result" must {
        behave like futureWithResult { test ⇒
          val actor = system.actorOf(Props[TestActor])
          val future = actor ? "Hello"
          Await.ready(future, timeout.duration)
          test(future, "World")
          system.stop(actor)
        }
      }
      "throws an exception" must {
        behave like futureWithException[RuntimeException] { test ⇒
          filterException[RuntimeException] {
            val actor = system.actorOf(Props[TestActor])
            val future = actor ? "Failure"
            Await.ready(future, timeout.duration)
            test(future, "Expected exception; to test fault-tolerance")
            system.stop(actor)
          }
        }
      }
    }

    "using flatMap with an Actor" that {
      "will return a result" must {
        behave like futureWithResult { test ⇒
          val actor1 = system.actorOf(Props[TestActor])
          val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender ! s.toUpperCase } }))
          val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
          Await.ready(future, timeout.duration)
          test(future, "WORLD")
          system.stop(actor1)
          system.stop(actor2)
        }
      }
      "will throw an exception" must {
        behave like futureWithException[ArithmeticException] { test ⇒
          filterException[ArithmeticException] {
            val actor1 = system.actorOf(Props[TestActor])
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender ! Status.Failure(new ArithmeticException("/ by zero")) } }))
            val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
            Await.ready(future, timeout.duration)
            test(future, "/ by zero")
            system.stop(actor1)
            system.stop(actor2)
          }
        }
      }
      "will throw a MatchError when matching wrong type" must {
        behave like futureWithException[MatchError] { test ⇒
          filterException[MatchError] {
            val actor1 = system.actorOf(Props[TestActor])
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender ! s.toUpperCase } }))
            val future = actor1 ? "Hello" flatMap { case i: Int ⇒ actor2 ? i }
            Await.ready(future, timeout.duration)
            test(future, "World (of class java.lang.String)")
            system.stop(actor1)
            system.stop(actor2)
          }
        }
      }
    }

    "being tested" must {

      "compose with for-comprehensions" in {
        filterException[ClassCastException] {
          val actor = system.actorOf(Props(new Actor {
            def receive = {
              case s: String ⇒ sender ! s.length
              case i: Int    ⇒ sender ! (i * 2).toString
            }
          }))

          val future0 = actor ? "Hello"

          val future1 = for {
            a ← future0.mapTo[Int] // returns 5
            b ← (actor ? a).mapTo[String] // returns "10"
            c ← (actor ? 7).mapTo[String] // returns "14"
          } yield b + "-" + c

          val future2 = for {
            a ← future0.mapTo[Int]
            b ← (actor ? a).mapTo[Int]
            c ← (actor ? 7).mapTo[String]
          } yield b + "-" + c

          Await.result(future1, timeout.duration) must be("10-14")
          assert(checkType(future1, manifest[String]))
          intercept[ClassCastException] { Await.result(future2, timeout.duration) }
          system.stop(actor)
        }
      }

      "support pattern matching within a for-comprehension" in {
        filterException[MatchError] {
          case class Req[T](req: T)
          case class Res[T](res: T)
          val actor = system.actorOf(Props(new Actor {
            def receive = {
              case Req(s: String) ⇒ sender ! Res(s.length)
              case Req(i: Int)    ⇒ sender ! Res((i * 2).toString)
            }
          }))

          val future1 = for {
            Res(a: Int) ← actor ? Req("Hello")
            Res(b: String) ← actor ? Req(a)
            Res(c: String) ← actor ? Req(7)
          } yield b + "-" + c

          val future2 = for {
            Res(a: Int) ← actor ? Req("Hello")
            Res(b: Int) ← actor ? Req(a)
            Res(c: Int) ← actor ? Req(7)
          } yield b + "-" + c

          Await.result(future1, timeout.duration) must be("10-14")
          intercept[MatchError] { Await.result(future2, timeout.duration) }
          system.stop(actor)
        }
      }

      "recover from exceptions" in {
        filterException[RuntimeException] {
          val future1 = Future(5)
          val future2 = future1 map (_ / 0)
          val future3 = future2 map (_.toString)

          val future4 = future1 recover {
            case e: ArithmeticException ⇒ 0
          } map (_.toString)

          val future5 = future2 recover {
            case e: ArithmeticException ⇒ 0
          } map (_.toString)

          val future6 = future2 recover {
            case e: MatchError ⇒ 0
          } map (_.toString)

          val future7 = future3 recover { case e: ArithmeticException ⇒ "You got ERROR" }

          val actor = system.actorOf(Props[TestActor])

          val future8 = actor ? "Failure"
          val future9 = actor ? "Failure" recover {
            case e: RuntimeException ⇒ "FAIL!"
          }
          val future10 = actor ? "Hello" recover {
            case e: RuntimeException ⇒ "FAIL!"
          }
          val future11 = actor ? "Failure" recover { case _ ⇒ "Oops!" }

          Await.result(future1, timeout.duration) must be(5)
          intercept[ArithmeticException] { Await.result(future2, timeout.duration) }
          intercept[ArithmeticException] { Await.result(future3, timeout.duration) }
          Await.result(future4, timeout.duration) must be("5")
          Await.result(future5, timeout.duration) must be("0")
          intercept[ArithmeticException] { Await.result(future6, timeout.duration) }
          Await.result(future7, timeout.duration) must be("You got ERROR")
          intercept[RuntimeException] { Await.result(future8, timeout.duration) }
          Await.result(future9, timeout.duration) must be("FAIL!")
          Await.result(future10, timeout.duration) must be("World")
          Await.result(future11, timeout.duration) must be("Oops!")

          system.stop(actor)
        }
      }

      "recoverWith from exceptions" in {
        val o = new IllegalStateException("original")
        val r = new IllegalStateException("recovered")
        val yay = Promise.successful("yay!").future

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](o).future recoverWith { case _ if false == true ⇒ yay }, timeout.duration)
        } must be(o)

        Await.result(Promise.failed[String](o).future recoverWith { case _ ⇒ yay }, timeout.duration) must equal("yay!")

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](o).future recoverWith { case _ ⇒ Promise.failed[String](r).future }, timeout.duration)
        } must be(r)
      }

      "andThen like a boss" in {
        val q = new LinkedBlockingQueue[Int]
        for (i ← 1 to 1000) {
          Await.result(Future { q.add(1); 3 } andThen { case _ ⇒ q.add(2) } andThen { case Right(0) ⇒ q.add(Int.MaxValue) } andThen { case _ ⇒ q.add(3); }, timeout.duration) must be(3)
          q.poll() must be(1)
          q.poll() must be(2)
          q.poll() must be(3)
          q.clear()
        }
      }

      "firstCompletedOf" in {
        val futures = Vector.fill[Future[Int]](10)(Promise[Int]().future) :+ Promise.successful[Int](5).future
        Await.result(Future.firstCompletedOf(futures), timeout.duration) must be(5)
      }

      "find" in {
        val futures = for (i ← 1 to 10) yield Future { i }
        val result = Future.find[Int](futures)(_ == 3)
        Await.result(result, timeout.duration) must be(Some(3))

        val notFound = Future.find[Int](futures)(_ == 11)
        Await.result(notFound, timeout.duration) must be(None)
      }

      "fold" in {
        val actors = (1 to 10).toList map { _ ⇒
          system.actorOf(Props(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); sender.tell(add) }
          }))
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200))(timeout).mapTo[Int] }
        Await.result(Future.fold(futures)(0)(_ + _), timeout millis) must be(45)
      }

      "zip" in {
        val timeout = 10000 millis
        val f = new IllegalStateException("test")
        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](f).future zip Promise.successful("foo").future, timeout)
        } must be(f)

        intercept[IllegalStateException] {
          Await.result(Promise.successful("foo").future zip Promise.failed[String](f).future, timeout)
        } must be(f)

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](f).future zip Promise.failed[String](f).future, timeout)
        } must be(f)

        Await.result(Promise.successful("foo").future zip Promise.successful("foo").future, timeout) must be(("foo", "foo"))
      }

      "fold by composing" in {
        val actors = (1 to 10).toList map { _ ⇒
          system.actorOf(Props(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); sender.tell(add) }
          }))
        }
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200))(10000).mapTo[Int] }
        Await.result(futures.foldLeft(Future(0))((fr, fa) ⇒ for (r ← fr; a ← fa) yield (r + a)), timeout.duration) must be(45)
      }

      "fold with an exception" in {
        filterException[IllegalArgumentException] {
          val actors = (1 to 10).toList map { _ ⇒
            system.actorOf(Props(new Actor {
              def receive = {
                case (add: Int, wait: Int) ⇒
                  Thread.sleep(wait)
                  if (add == 6) sender ! Status.Failure(new IllegalArgumentException("shouldFoldResultsWithException: expected"))
                  else sender.tell(add)
              }
            }))
          }
          val timeout = 10000
          def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 100))(timeout).mapTo[Int] }
          intercept[Throwable] { Await.result(Future.fold(futures)(0)(_ + _), timeout millis) }.getMessage must be("shouldFoldResultsWithException: expected")
        }
      }

      "fold mutable zeroes safely" in {
        import scala.collection.mutable.ArrayBuffer
        def test(testNumber: Int) {
          val fs = (0 to 1000) map (i ⇒ Future(i))
          val f = Future.fold(fs)(ArrayBuffer.empty[AnyRef]) {
            case (l, i) if i % 2 == 0 ⇒ l += i.asInstanceOf[AnyRef]
            case (l, _)               ⇒ l
          }
          val result = Await.result(f.mapTo[ArrayBuffer[Int]], 10000 millis).sum

          assert(result === 250500)
        }

        (1 to 100) foreach test //Make sure it tries to provoke the problem
      }

      "return zero value if folding empty list" in {
        Await.result(Future.fold(List[Future[Int]]())(0)(_ + _), timeout.duration) must be(0)
      }

      "shouldReduceResults" in {
        val actors = (1 to 10).toList map { _ ⇒
          system.actorOf(Props(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); sender.tell(add) }
          }))
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200))(timeout).mapTo[Int] }
        assert(Await.result(Future.reduce(futures)(_ + _), timeout millis) === 45)
      }

      "shouldReduceResultsWithException" in {
        filterException[IllegalArgumentException] {
          val actors = (1 to 10).toList map { _ ⇒
            system.actorOf(Props(new Actor {
              def receive = {
                case (add: Int, wait: Int) ⇒
                  Thread.sleep(wait)
                  if (add == 6) sender ! Status.Failure(new IllegalArgumentException("shouldFoldResultsWithException: expected"))
                  else sender.tell(add)
              }
            }))
          }
          val timeout = 10000
          def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 100))(timeout).mapTo[Int] }
          intercept[Throwable] { Await.result(Future.reduce(futures)(_ + _), timeout millis) }.getMessage must be === "shouldFoldResultsWithException: expected"
        }
      }

      "shouldReduceThrowIAEOnEmptyInput" in {
        filterException[IllegalArgumentException] {
          intercept[java.util.NoSuchElementException] { Await.result(Future.reduce(List[Future[Int]]())(_ + _), timeout.duration) }
        }
      }

      "receiveShouldExecuteOnComplete" in {
        val latch = new TestLatch
        val actor = system.actorOf(Props[TestActor])
        actor ? "Hello" onSuccess { case "World" ⇒ latch.open() }
        Await.ready(latch, 5 seconds)
        system.stop(actor)
      }

      "shouldTraverseFutures" in {
        val oddActor = system.actorOf(Props(new Actor {
          var counter = 1
          def receive = {
            case 'GetNext ⇒
              sender ! counter
              counter += 2
          }
        }))

        val oddFutures = List.fill(100)(oddActor ? 'GetNext mapTo manifest[Int])

        assert(Await.result(Future.sequence(oddFutures), timeout.duration).sum === 10000)
        system.stop(oddActor)

        val list = (1 to 100).toList
        assert(Await.result(Future.traverse(list)(x ⇒ Future(x * 2 - 1)), timeout.duration).sum === 10000)
      }

      "shouldHandleThrowables" in {
        class ThrowableTest(m: String) extends Throwable(m)

        EventFilter[ThrowableTest](occurrences = 4) intercept {
          val f1 = Future[Any] { throw new ThrowableTest("test") }
          intercept[ThrowableTest] { Await.result(f1, timeout.duration) }

          val latch = new TestLatch
          val f2 = Future { Await.ready(latch, 5 seconds); "success" }
          f2 foreach (_ ⇒ throw new ThrowableTest("dispatcher foreach"))
          f2 onSuccess { case _ ⇒ throw new ThrowableTest("dispatcher receive") }
          val f3 = f2 map (s ⇒ s.toUpperCase)
          latch.open()
          assert(Await.result(f2, timeout.duration) === "success")
          f2 foreach (_ ⇒ throw new ThrowableTest("current thread foreach"))
          f2 onSuccess { case _ ⇒ throw new ThrowableTest("current thread receive") }
          assert(Await.result(f3, timeout.duration) === "SUCCESS")
        }
      }

      "shouldBlockUntilResult" in {
        val latch = new TestLatch

        val f = Future { Await.ready(latch, 5 seconds); 5 }
        val f2 = Future { Await.result(f, timeout.duration) + 5 }

        intercept[TimeoutException](Await.ready(f2, 100 millis))
        latch.open()
        assert(Await.result(f2, timeout.duration) === 10)

        val f3 = Future { Thread.sleep(100); 5 }
        filterException[TimeoutException] { intercept[TimeoutException] { Await.ready(f3, 0 millis) } }
      }

      //FIXME DATAFLOW
      /*"futureComposingWithContinuations" in {
        import Future.flow

        val actor = system.actorOf(Props[TestActor])

        val x = Future("Hello")
        val y = x flatMap (actor ? _) mapTo manifest[String]

        val r = flow(x() + " " + y() + "!")

        assert(Await.result(r, timeout.duration) === "Hello World!")

        system.stop(actor)
      }

      "futureComposingWithContinuationsFailureDivideZero" in {
        filterException[ArithmeticException] {
          import Future.flow

          val x = Future("Hello")
          val y = x map (_.length)

          val r = flow(x() + " " + y.map(_ / 0).map(_.toString).apply, 100)

          intercept[java.lang.ArithmeticException](Await.result(r, timeout.duration))
        }
      }

      "futureComposingWithContinuationsFailureCastInt" in {
        filterException[ClassCastException] {
          import Future.flow

          val actor = system.actorOf(Props[TestActor])

          val x = Future(3)
          val y = (actor ? "Hello").mapTo[Int]

          val r = flow(x() + y(), 100)

          intercept[ClassCastException](Await.result(r, timeout.duration))
        }
      }

      "futureComposingWithContinuationsFailureCastNothing" in {
        filterException[ClassCastException] {
          import Future.flow

          val actor = system.actorOf(Props[TestActor])

          val x = Future("Hello")
          val y = actor ? "Hello" mapTo manifest[Nothing]

          val r = flow(x() + y())

          intercept[ClassCastException](Await.result(r, timeout.duration))
        }
      }

      "futureCompletingWithContinuations" in {
        import Future.flow

        val x, y, z = Promise[Int]()
        val ly, lz = new TestLatch

        val result = flow {
          y completeWith x
          ly.open() // not within continuation

          z << x
          lz.open() // within continuation, will wait for 'z' to complete
          z() + y()
        }

        Await.ready(ly, 100 milliseconds)
        intercept[TimeoutException] { Await.ready(lz, 100 milliseconds) }

        flow { x << 5 }

        assert(Await.result(y, timeout.duration) === 5)
        assert(Await.result(z, timeout.duration) === 5)
        Await.ready(lz, timeout.duration)
        assert(Await.result(result, timeout.duration) === 10)

        val a, b, c = Promise[Int]()

        val result2 = flow {
          val n = (a << c).value.get.right.get + 10
          b << (c() - 2)
          a() + n * b()
        }

        c completeWith Future(5)

        assert(Await.result(a, timeout.duration) === 5)
        assert(Await.result(b, timeout.duration) === 3)
        assert(Await.result(result2, timeout.duration) === 50)
      }

      "futureDataFlowShouldEmulateBlocking1" in {
        import Future.flow

        val one, two = Promise[Int]()
        val simpleResult = flow {
          one() + two()
        }

        assert(List(one, two, simpleResult).forall(_.isCompleted == false))

        flow { one << 1 }

        Await.ready(one, 1 minute)

        assert(one.isCompleted)
        assert(List(two, simpleResult).forall(_.isCompleted == false))

        flow { two << 9 }

        Await.ready(two, 1 minute)

        assert(List(one, two).forall(_.isCompleted == true))
        assert(Await.result(simpleResult, timeout.duration) === 10)

      }

      "futureDataFlowShouldEmulateBlocking2" in {
        import Future.flow
        val x1, x2, y1, y2 = Promise[Int]()
        val lx, ly, lz = new TestLatch
        val result = flow {
          lx.open()
          x1 << y1
          ly.open()
          x2 << y2
          lz.open()
          x1() + x2()
        }
        Await.ready(lx, 2 seconds)
        assert(!ly.isOpen)
        assert(!lz.isOpen)
        assert(List(x1, x2, y1, y2).forall(_.isCompleted == false))

        flow { y1 << 1 } // When this is set, it should cascade down the line

        Await.ready(ly, 2 seconds)
        assert(Await.result(x1, 1 minute) === 1)
        assert(!lz.isOpen)

        flow { y2 << 9 } // When this is set, it should cascade down the line

        Await.ready(lz, 2 seconds)
        assert(Await.result(x2, 1 minute) === 9)

        assert(List(x1, x2, y1, y2).forall(_.isCompleted))

        assert(Await.result(result, 1 minute) === 10)
      }

      "dataFlowAPIshouldbeSlick" in {
        import Future.flow

        val i1, i2, s1, s2 = new TestLatch

        val callService1 = Future { i1.open(); Await.ready(s1, TestLatch.DefaultTimeout); 1 }
        val callService2 = Future { i2.open(); Await.ready(s2, TestLatch.DefaultTimeout); 9 }

        val result = flow { callService1() + callService2() }

        assert(!s1.isOpen)
        assert(!s2.isOpen)
        assert(!result.isCompleted)
        Await.ready(i1, 2 seconds)
        Await.ready(i2, 2 seconds)
        s1.open()
        s2.open()
        assert(Await.result(result, timeout.duration) === 10)
      }

      "futureCompletingWithContinuationsFailure" in {
        filterException[ArithmeticException] {
          import Future.flow

          val x, y, z = Promise[Int]()
          val ly, lz = new TestLatch

          val result = flow {
            y << x
            ly.open()
            val oops = 1 / 0
            z << x
            lz.open()
            z() + y() + oops
          }
          intercept[TimeoutException] { Await.ready(ly, 100 milliseconds) }
          intercept[TimeoutException] { Await.ready(lz, 100 milliseconds) }
          flow { x << 5 }

          assert(Await.result(y, timeout.duration) === 5)
          intercept[java.lang.ArithmeticException](Await.result(result, timeout.duration))
          assert(z.value === None)
          assert(!lz.isOpen)
        }
      }

      "futureContinuationsShouldNotBlock" in {
        import Future.flow

        val latch = new TestLatch
        val future = Future {
          Await.ready(latch, TestLatch.DefaultTimeout)
          "Hello"
        }

        val result = flow {
          Some(future()).filter(_ == "Hello")
        }

        assert(!result.isCompleted)

        latch.open()

        assert(Await.result(result, timeout.duration) === Some("Hello"))
      }

      "futureFlowShouldBeTypeSafe" in {
        import Future.flow

        val rString = flow {
          val x = Future(5)
          x().toString
        }

        val rInt = flow {
          val x = rString.apply
          val y = Future(5)
          x.length + y()
        }

        assert(checkType(rString, manifest[String]))
        assert(checkType(rInt, manifest[Int]))
        assert(!checkType(rInt, manifest[String]))
        assert(!checkType(rInt, manifest[Nothing]))
        assert(!checkType(rInt, manifest[Any]))

        Await.result(rString, timeout.duration)
        Await.result(rInt, timeout.duration)
      }

      "futureFlowSimpleAssign" in {
        import Future.flow

        val x, y, z = Promise[Int]()

        flow {
          z << x() + y()
        }
        flow { x << 40 }
        flow { y << 2 }

        assert(Await.result(z, timeout.duration) === 42)
      }*/

      "run callbacks async" in {
        val latch = Vector.fill(10)(new TestLatch)

        val f1 = Future { latch(0).open(); Await.ready(latch(1), TestLatch.DefaultTimeout); "Hello" }
        val f2 = f1 map { s ⇒ latch(2).open(); Await.ready(latch(3), TestLatch.DefaultTimeout); s.length }
        f2 foreach (_ ⇒ latch(4).open())

        Await.ready(latch(0), TestLatch.DefaultTimeout)

        f1 must not be ('completed)
        f2 must not be ('completed)

        latch(1).open()
        Await.ready(latch(2), TestLatch.DefaultTimeout)

        f1 must be('completed)
        f2 must not be ('completed)

        val f3 = f1 map { s ⇒ latch(5).open(); Await.ready(latch(6), TestLatch.DefaultTimeout); s.length * 2 }
        f3 foreach (_ ⇒ latch(3).open())

        Await.ready(latch(5), TestLatch.DefaultTimeout)

        f3 must not be ('completed)

        latch(6).open()
        Await.ready(latch(4), TestLatch.DefaultTimeout)

        f2 must be('completed)
        f3 must be('completed)

        val p1 = Promise[String]()
        val f4 = p1.future map { s ⇒ latch(7).open(); Await.ready(latch(8), TestLatch.DefaultTimeout); s.length }
        f4 foreach (_ ⇒ latch(9).open())

        p1 must not be ('completed)
        f4 must not be ('completed)

        p1 complete Right("Hello")

        Await.ready(latch(7), TestLatch.DefaultTimeout)

        p1 must be('completed)
        f4 must not be ('completed)

        latch(8).open()
        Await.ready(latch(9), TestLatch.DefaultTimeout)

        Await.ready(f4, timeout.duration) must be('completed)
      }

      "should not deadlock with nested await (ticket 1313)" in {
        val simple = Future() map (_ ⇒ Await.result((Future(()) map (_ ⇒ ())), timeout.duration))
        Await.ready(simple, timeout.duration) must be('completed)

        val l1, l2 = new TestLatch
        val complex = Future() map { _ ⇒
          //FIXME implement _taskStack for Futures
          val nested = Future(())
          nested foreach (_ ⇒ l1.open())
          Await.ready(l1, TestLatch.DefaultTimeout) // make sure nested is completed
          nested foreach (_ ⇒ l2.open())
          Await.ready(l2, TestLatch.DefaultTimeout)
        }
        Await.ready(complex, timeout.duration) must be('completed)
      }

      //FIXME DATAFLOW
      /*"should capture first exception with dataflow" in {
        import Future.flow
        val f1 = flow { 40 / 0 }
        intercept[java.lang.ArithmeticException](Await result (f1, TestLatch.DefaultTimeout))
      }*/

    }
  }

  def emptyFuture(f: (Future[Any] ⇒ Unit) ⇒ Unit) {
    "not be completed" in { f(_ must not be ('completed)) }
    "not contain a value" in { f(_.value must be(None)) }
  }

  def futureWithResult(f: ((Future[Any], Any) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future must be('completed)) }
    "contain a value" in { f((future, result) ⇒ future.value must be(Some(Right(result)))) }
    "return result with 'get'" in { f((future, result) ⇒ Await.result(future, timeout.duration) must be(result)) }
    "return result with 'Await.result'" in { f((future, result) ⇒ Await.result(future, timeout.duration) must be(result)) }
    "not timeout" in { f((future, _) ⇒ Await.ready(future, 0 millis)) }
    "filter result" in {
      f { (future, result) ⇒
        Await.result((future filter (_ ⇒ true)), timeout.duration) must be(result)
        (evaluating { Await.result((future filter (_ ⇒ false)), timeout.duration) } must produce[java.util.NoSuchElementException]).getMessage must endWith(result.toString)
      }
    }
    "transform result with map" in { f((future, result) ⇒ Await.result((future map (_.toString.length)), timeout.duration) must be(result.toString.length)) }
    "compose result with flatMap" in {
      f { (future, result) ⇒
        val r = for (r ← future; p ← Promise.successful("foo").future) yield r.toString + p
        Await.result(r, timeout.duration) must be(result.toString + "foo")
      }
    }
    "perform action with foreach" in {
      f { (future, result) ⇒
        val p = Promise[Any]()
        future foreach p.success
        Await.result(p.future, timeout.duration) must be(result)
      }
    }
    "zip properly" in {
      f { (future, result) ⇒
        Await.result(future zip Promise.successful("foo").future, timeout.duration) must be((result, "foo"))
        (evaluating { Await.result(future zip Promise.failed(new RuntimeException("ohnoes")).future, timeout.duration) } must produce[RuntimeException]).getMessage must be("ohnoes")
      }
    }
    "not recover from exception" in { f((future, result) ⇒ Await.result(future.recover({ case _ ⇒ "pigdog" }), timeout.duration) must be(result)) }
    "perform action on result" in {
      f { (future, result) ⇒
        val p = Promise[Any]()
        future.onSuccess { case x ⇒ p.success(x) }
        Await.result(p.future, timeout.duration) must be(result)
      }
    }
    "not project a failure" in { f((future, result) ⇒ (evaluating { Await.result(future.failed, timeout.duration) } must produce[NoSuchElementException]).getMessage must be("Future.failed not completed with a throwable.")) }
    "not perform action on exception" is pending
    "cast using mapTo" in { f((future, result) ⇒ Await.result(future.mapTo[Boolean].recover({ case _: ClassCastException ⇒ false }), timeout.duration) must be(false)) }
  }

  def futureWithException[E <: Throwable: Manifest](f: ((Future[Any], String) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future must be('completed)) }
    "contain a value" in {
      f((future, message) ⇒ {
        future.value must be('defined)
        future.value.get must be('left)
        future.value.get.left.get.getMessage must be(message)
      })
    }
    "throw exception with 'get'" in { f((future, message) ⇒ (evaluating { Await.result(future, timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    "throw exception with 'Await.result'" in { f((future, message) ⇒ (evaluating { Await.result(future, timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    "retain exception with filter" in {
      f { (future, message) ⇒
        (evaluating { Await.result(future filter (_ ⇒ true), timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)
        (evaluating { Await.result(future filter (_ ⇒ false), timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)
      }
    }
    "retain exception with map" in { f((future, message) ⇒ (evaluating { Await.result(future map (_.toString.length), timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    "retain exception with flatMap" in { f((future, message) ⇒ (evaluating { Await.result(future flatMap (_ ⇒ Promise.successful[Any]("foo").future), timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
    "not perform action with foreach" is pending

    "zip properly" in {
      f { (future, message) ⇒ (evaluating { Await.result(future zip Promise.successful("foo").future, timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message) }
    }
    "recover from exception" in { f((future, message) ⇒ Await.result(future.recover({ case e if e.getMessage == message ⇒ "pigdog" }), timeout.duration) must be("pigdog")) }
    "not perform action on result" is pending
    "project a failure" in { f((future, message) ⇒ Await.result(future.failed, timeout.duration).getMessage must be(message)) }
    "perform action on exception" in {
      f { (future, message) ⇒
        val p = Promise[Any]()
        future.onFailure { case _ ⇒ p.success(message) }
        Await.result(p.future, timeout.duration) must be(message)
      }
    }
    "always cast successfully using mapTo" in { f((future, message) ⇒ (evaluating { Await.result(future.mapTo[java.lang.Thread], timeout.duration) } must produce[java.lang.Exception]).getMessage must be(message)) }
  }

  sealed trait IntAction { def apply(that: Int): Int }
  case class IntAdd(n: Int) extends IntAction { def apply(that: Int) = that + n }
  case class IntSub(n: Int) extends IntAction { def apply(that: Int) = that - n }
  case class IntMul(n: Int) extends IntAction { def apply(that: Int) = that * n }
  case class IntDiv(n: Int) extends IntAction { def apply(that: Int) = that / n }

  sealed trait FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int]
    def /:(that: Future[Int]): Future[Int]
  }

  case class MapAction(action: IntAction) extends FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int] = that match {
      case Left(e)  ⇒ that
      case Right(r) ⇒ try { Right(action(r)) } catch { case e if NonFatal(e) ⇒ Left(e) }
    }
    def /:(that: Future[Int]): Future[Int] = that map action.apply
  }

  case class FlatMapAction(action: IntAction) extends FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int] = that match {
      case Left(e)  ⇒ that
      case Right(r) ⇒ try { Right(action(r)) } catch { case e if NonFatal(e) ⇒ Left(e) }
    }
    def /:(that: Future[Int]): Future[Int] = that flatMap (n ⇒ Future(action(n)))
  }

  implicit def arbFuture: Arbitrary[Future[Int]] = Arbitrary(for (n ← arbitrary[Int]) yield Future(n))

  implicit def arbFutureAction: Arbitrary[FutureAction] = Arbitrary {

    val genIntAction = for {
      n ← arbitrary[Int]
      a ← oneOf(IntAdd(n), IntSub(n), IntMul(n), IntDiv(n))
    } yield a

    val genMapAction = genIntAction map (MapAction(_))

    val genFlatMapAction = genIntAction map (FlatMapAction(_))

    oneOf(genMapAction, genFlatMapAction)

  }

  def checkType[A: Manifest, B](in: Future[A], refmanifest: Manifest[B]): Boolean = manifest[A] == refmanifest
}
