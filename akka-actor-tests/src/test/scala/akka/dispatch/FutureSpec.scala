package akka.dispatch

import language.postfixOps

import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.Checkers
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import akka.actor._
import akka.testkit.{ EventFilter, filterException, AkkaSpec, DefaultTimeout, TestLatch }
import scala.concurrent.{ Await, Awaitable, Future, Promise }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import org.scalatest.junit.JUnitSuiteLike
import scala.runtime.NonLocalReturnControl
import akka.pattern.ask
import java.lang.{ IllegalStateException, ArithmeticException }
import java.util.concurrent._
import scala.reflect.{ ClassTag, classTag }
import scala.util.{ Failure, Success, Try }

object FutureSpec {

  def ready[T](awaitable: Awaitable[T], atMost: Duration): awaitable.type =
    try Await.ready(awaitable, atMost) catch {
      case t: TimeoutException ⇒ throw t
      case e if NonFatal(e)    ⇒ awaitable //swallow
    }

  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒ sender() ! "World"
      case "Failure" ⇒
        sender() ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
      case "NoReply" ⇒
    }
  }

  class TestDelayActor(await: TestLatch) extends Actor {
    def receive = {
      case "Hello" ⇒
        FutureSpec.ready(await, TestLatch.DefaultTimeout); sender() ! "World"
      case "NoReply" ⇒ FutureSpec.ready(await, TestLatch.DefaultTimeout)
      case "Failure" ⇒
        FutureSpec.ready(await, TestLatch.DefaultTimeout)
        sender() ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
    }
  }

  final case class Req[T](req: T)
  final case class Res[T](res: T)

  sealed trait IntAction { def apply(that: Int): Int }
  final case class IntAdd(n: Int) extends IntAction { def apply(that: Int) = that + n }
  final case class IntSub(n: Int) extends IntAction { def apply(that: Int) = that - n }
  final case class IntMul(n: Int) extends IntAction { def apply(that: Int) = that * n }
  final case class IntDiv(n: Int) extends IntAction { def apply(that: Int) = that / n }

  sealed trait FutureAction {
    def /:(that: Try[Int]): Try[Int]
    def /:(that: Future[Int]): Future[Int]
  }

  final case class MapAction(action: IntAction)(implicit ec: ExecutionContext) extends FutureAction {
    def /:(that: Try[Int]): Try[Int] = that map action.apply
    def /:(that: Future[Int]): Future[Int] = that map action.apply
  }

  final case class FlatMapAction(action: IntAction)(implicit ec: ExecutionContext) extends FutureAction {
    def /:(that: Try[Int]): Try[Int] = that map action.apply
    def /:(that: Future[Int]): Future[Int] = that flatMap (n ⇒ Future.successful(action(n)))
  }
}

class JavaFutureSpec extends JavaFutureTests with JUnitSuiteLike

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FutureSpec extends AkkaSpec with Checkers with BeforeAndAfterAll with DefaultTimeout {
  import FutureSpec._
  implicit val ec: ExecutionContext = system.dispatcher
  "A Promise" when {
    "never completed" must {
      behave like emptyFuture(_(Promise().future))
      "return supplied value on timeout" in {
        val failure = Promise.failed[String](new RuntimeException("br0ken")).future
        val otherFailure = Promise.failed[String](new RuntimeException("last")).future
        val empty = Promise[String]().future
        val timedOut = Promise.successful[String]("Timedout").future

        Await.result(failure fallbackTo timedOut, timeout.duration) should ===("Timedout")
        Await.result(timedOut fallbackTo empty, timeout.duration) should ===("Timedout")
        Await.result(failure fallbackTo failure fallbackTo timedOut, timeout.duration) should ===("Timedout")
        intercept[RuntimeException] {
          Await.result(failure fallbackTo otherFailure, timeout.duration)
        }.getMessage should ===("br0ken")
      }
    }
    "completed with a result" must {
      val result = "test value"
      val future = Promise[String]().complete(Success(result)).future
      behave like futureWithResult(_(future, result))
    }
    "completed with an exception" must {
      val message = "Expected Exception"
      val future = Promise[String]().complete(Failure(new RuntimeException(message))).future
      behave like futureWithException[RuntimeException](_(future, message))
    }
    "completed with an InterruptedException" must {
      val message = "Boxed InterruptedException"
      val future = Promise[String]().complete(Failure(new InterruptedException(message))).future
      behave like futureWithException[RuntimeException](_(future, message))
    }
    "completed with a NonLocalReturnControl" must {
      val result = "test value"
      val future = Promise[String]().complete(Failure(new NonLocalReturnControl[String]("test", result))).future
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
        Await.result(result, timeout.duration) should ===("Hi A")
      } finally {
        A.shutdown()
        B.shutdown()
      }
    }
  }

  "A Future" when {

    "awaiting a result" which {
      "is not completed" must {
        behave like emptyFuture { test ⇒
          val latch = new TestLatch
          val result = "test value"
          val future = Future {
            FutureSpec.ready(latch, TestLatch.DefaultTimeout)
            result
          }
          test(future)
          latch.open()
          FutureSpec.ready(future, timeout.duration)
        }
      }
      "is completed" must {
        behave like futureWithResult { test ⇒
          val latch = new TestLatch
          val result = "test value"
          val future = Future {
            FutureSpec.ready(latch, TestLatch.DefaultTimeout)
            result
          }
          latch.open()
          FutureSpec.ready(future, timeout.duration)
          test(future, result)
        }
      }
      "has actions applied" must {
        "pass checks" in {
          filterException[ArithmeticException] {
            check({ (future: Future[Int], actions: List[FutureAction]) ⇒
              def wrap[T](f: Future[T]): Try[T] = FutureSpec.ready(f, timeout.duration).value.get
              val result = (future /: actions)(_ /: _)
              val expected = (wrap(future) /: actions)(_ /: _)
              ((wrap(result), expected) match {
                case (Success(a), Success(b)) ⇒ a == b
                case (Failure(a), Failure(b)) if a.toString == b.toString ⇒ true
                case (Failure(a), Failure(b)) if a.getStackTrace.isEmpty || b.getStackTrace.isEmpty ⇒ a.getClass.toString == b.getClass.toString
                case _ ⇒ false
              }) :| result.value.get.toString + " is expected to be " + expected.toString
            }, minSuccessful(10000), workers(4))
          }
        }
      }
    }

    "from an Actor" which {
      "returns a result" must {
        behave like futureWithResult { test ⇒
          val actor = system.actorOf(Props[TestActor])
          val future = actor ? "Hello"
          FutureSpec.ready(future, timeout.duration)
          test(future, "World")
          system.stop(actor)
        }
      }
      "throws an exception" must {
        behave like futureWithException[RuntimeException] { test ⇒
          filterException[RuntimeException] {
            val actor = system.actorOf(Props[TestActor])
            val future = actor ? "Failure"
            FutureSpec.ready(future, timeout.duration)
            test(future, "Expected exception; to test fault-tolerance")
            system.stop(actor)
          }
        }
      }
    }

    "using flatMap with an Actor" which {
      "will return a result" must {
        behave like futureWithResult { test ⇒
          val actor1 = system.actorOf(Props[TestActor])
          val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender() ! s.toUpperCase } }))
          val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
          FutureSpec.ready(future, timeout.duration)
          test(future, "WORLD")
          system.stop(actor1)
          system.stop(actor2)
        }
      }
      "will throw an exception" must {
        behave like futureWithException[ArithmeticException] { test ⇒
          filterException[ArithmeticException] {
            val actor1 = system.actorOf(Props[TestActor])
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender() ! Status.Failure(new ArithmeticException("/ by zero")) } }))
            val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
            FutureSpec.ready(future, timeout.duration)
            test(future, "/ by zero")
            system.stop(actor1)
            system.stop(actor2)
          }
        }
      }
      "will throw a NoSuchElementException when matching wrong type" must {
        behave like futureWithException[NoSuchElementException] { test ⇒
          filterException[NoSuchElementException] {
            val actor1 = system.actorOf(Props[TestActor])
            val actor2 = system.actorOf(Props(new Actor { def receive = { case s: String ⇒ sender() ! s.toUpperCase } }))
            val future = actor1 ? "Hello" flatMap { case i: Int ⇒ actor2 ? i }
            FutureSpec.ready(future, timeout.duration)
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
              case s: String ⇒ sender() ! s.length
              case i: Int    ⇒ sender() ! (i * 2).toString
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

          Await.result(future1, timeout.duration) should ===("10-14")
          assert(checkType(future1, classTag[String]))
          intercept[ClassCastException] { Await.result(future2, timeout.duration) }
          system.stop(actor)
        }
      }

      "support pattern matching within a for-comprehension" in {
        filterException[NoSuchElementException] {
          val actor = system.actorOf(Props(new Actor {
            def receive = {
              case Req(s: String) ⇒ sender() ! Res(s.length)
              case Req(i: Int)    ⇒ sender() ! Res((i * 2).toString)
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

          Await.result(future1, timeout.duration) should ===("10-14")
          intercept[NoSuchElementException] { Await.result(future2, timeout.duration) }
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

          Await.result(future1, timeout.duration) should ===(5)
          intercept[ArithmeticException] { Await.result(future2, timeout.duration) }
          intercept[ArithmeticException] { Await.result(future3, timeout.duration) }
          Await.result(future4, timeout.duration) should ===("5")
          Await.result(future5, timeout.duration) should ===("0")
          intercept[ArithmeticException] { Await.result(future6, timeout.duration) }
          Await.result(future7, timeout.duration) should ===("You got ERROR")
          intercept[RuntimeException] { Await.result(future8, timeout.duration) }
          Await.result(future9, timeout.duration) should ===("FAIL!")
          Await.result(future10, timeout.duration) should ===("World")
          Await.result(future11, timeout.duration) should ===("Oops!")

          system.stop(actor)
        }
      }

      "recoverWith from exceptions" in {
        val o = new IllegalStateException("original")
        val r = new IllegalStateException("recovered")
        val yay = Promise.successful("yay!").future

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](o).future recoverWith { case _ if false == true ⇒ yay }, timeout.duration)
        } should ===(o)

        Await.result(Promise.failed[String](o).future recoverWith { case _ ⇒ yay }, timeout.duration) should ===("yay!")

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](o).future recoverWith { case _ ⇒ Promise.failed[String](r).future }, timeout.duration)
        } should ===(r)
      }

      "andThen like a boss" in {
        val q = new LinkedBlockingQueue[Int]
        for (i ← 1 to 1000) {
          Await.result(Future { q.add(1); 3 } andThen { case _ ⇒ q.add(2) } andThen { case Success(0) ⇒ q.add(Int.MaxValue) } andThen { case _ ⇒ q.add(3); }, timeout.duration) should ===(3)
          q.poll() should ===(1)
          q.poll() should ===(2)
          q.poll() should ===(3)
          q.clear()
        }
      }

      "firstCompletedOf" in {
        val futures = Vector.fill[Future[Int]](10)(Promise[Int]().future) :+ Promise.successful[Int](5).future
        Await.result(Future.firstCompletedOf(futures), timeout.duration) should ===(5)
      }

      "find" in {
        val futures = for (i ← 1 to 10) yield Future { i }
        val result = Future.find[Int](futures)(_ == 3)
        Await.result(result, timeout.duration) should ===(Some(3))

        val notFound = Future.find[Int](futures)(_ == 11)
        Await.result(notFound, timeout.duration) should ===(None)
      }

      "fold" in {
        Await.result(Future.fold((1 to 10).toList map { i ⇒ Future(i) })(0)(_ + _), remainingOrDefault) should ===(55)
      }

      "zip" in {
        val timeout = 10000 millis
        val f = new IllegalStateException("test")
        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](f).future zip Promise.successful("foo").future, timeout)
        } should ===(f)

        intercept[IllegalStateException] {
          Await.result(Promise.successful("foo").future zip Promise.failed[String](f).future, timeout)
        } should ===(f)

        intercept[IllegalStateException] {
          Await.result(Promise.failed[String](f).future zip Promise.failed[String](f).future, timeout)
        } should ===(f)

        Await.result(Promise.successful("foo").future zip Promise.successful("foo").future, timeout) should ===(("foo", "foo"))
      }

      "fold by composing" in {
        val futures = (1 to 10).toList map { i ⇒ Future(i) }
        Await.result(futures.foldLeft(Future(0))((fr, fa) ⇒ for (r ← fr; a ← fa) yield (r + a)), timeout.duration) should ===(55)
      }

      "fold with an exception" in {
        filterException[IllegalArgumentException] {
          val futures = (1 to 10).toList map {
            case 6 ⇒ Future(throw new IllegalArgumentException("shouldFoldResultsWithException: expected"))
            case i ⇒ Future(i)
          }
          intercept[Throwable] { Await.result(Future.fold(futures)(0)(_ + _), remainingOrDefault) }.getMessage should ===("shouldFoldResultsWithException: expected")
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
        Await.result(Future.fold(List[Future[Int]]())(0)(_ + _), timeout.duration) should ===(0)
      }

      "reduce results" in {
        val futures = (1 to 10).toList map { i ⇒ Future(i) }
        assert(Await.result(Future.reduce(futures)(_ + _), remainingOrDefault) === 55)
      }

      "reduce results with Exception" in {
        filterException[IllegalArgumentException] {
          val futures = (1 to 10).toList map {
            case 6 ⇒ Future(throw new IllegalArgumentException("shouldReduceResultsWithException: expected"))
            case i ⇒ Future(i)
          }
          intercept[Throwable] { Await.result(Future.reduce(futures)(_ + _), remainingOrDefault) }.getMessage should ===("shouldReduceResultsWithException: expected")
        }
      }

      "throw IllegalArgumentException on empty input to reduce" in {
        filterException[IllegalArgumentException] {
          intercept[java.util.NoSuchElementException] { Await.result(Future.reduce(List[Future[Int]]())(_ + _), timeout.duration) }
        }
      }

      "execute onSuccess when received ask reply" in {
        val latch = new TestLatch
        val actor = system.actorOf(Props[TestActor])
        actor ? "Hello" onSuccess { case "World" ⇒ latch.open() }
        FutureSpec.ready(latch, 5 seconds)
        system.stop(actor)
      }

      "traverse Futures" in {
        val oddActor = system.actorOf(Props(new Actor {
          var counter = 1
          def receive = {
            case 'GetNext ⇒
              sender() ! counter
              counter += 2
          }
        }))

        val oddFutures = List.fill(100)(oddActor ? 'GetNext mapTo classTag[Int])

        assert(Await.result(Future.sequence(oddFutures), timeout.duration).sum === 10000)
        system.stop(oddActor)

        val list = (1 to 100).toList
        assert(Await.result(Future.traverse(list)(x ⇒ Future(x * 2 - 1)), timeout.duration).sum === 10000)
      }

      "handle Throwables" in {
        class ThrowableTest(m: String) extends Throwable(m)

        EventFilter[ThrowableTest](occurrences = 4) intercept {
          val f1 = Future[Any] { throw new ThrowableTest("test") }
          intercept[ThrowableTest] { Await.result(f1, timeout.duration) }

          val latch = new TestLatch
          val f2 = Future { FutureSpec.ready(latch, 5 seconds); "success" }
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

      "block until result" in {
        val latch = new TestLatch

        val f = Future { FutureSpec.ready(latch, 5 seconds); 5 }
        val f2 = Future { Await.result(f, timeout.duration) + 5 }

        intercept[TimeoutException](FutureSpec.ready(f2, 100 millis))
        latch.open()
        assert(Await.result(f2, timeout.duration) === 10)

        val f3 = Promise[Int]().future
        filterException[TimeoutException] { intercept[TimeoutException] { FutureSpec.ready(f3, 0 millis) } }
      }

      "run callbacks async" in {
        val latch = Vector.fill(10)(new TestLatch)

        val f1 = Future { latch(0).open(); FutureSpec.ready(latch(1), TestLatch.DefaultTimeout); "Hello" }
        val f2 = f1 map { s ⇒ latch(2).open(); FutureSpec.ready(latch(3), TestLatch.DefaultTimeout); s.length }
        f2 foreach (_ ⇒ latch(4).open())

        FutureSpec.ready(latch(0), TestLatch.DefaultTimeout)

        f1 should not be ('completed)
        f2 should not be ('completed)

        latch(1).open()
        FutureSpec.ready(latch(2), TestLatch.DefaultTimeout)

        f1 should be('completed)
        f2 should not be ('completed)

        val f3 = f1 map { s ⇒ latch(5).open(); FutureSpec.ready(latch(6), TestLatch.DefaultTimeout); s.length * 2 }
        f3 foreach (_ ⇒ latch(3).open())

        FutureSpec.ready(latch(5), TestLatch.DefaultTimeout)

        f3 should not be ('completed)

        latch(6).open()
        FutureSpec.ready(latch(4), TestLatch.DefaultTimeout)

        f2 should be('completed)
        f3 should be('completed)

        val p1 = Promise[String]()
        val f4 = p1.future map { s ⇒ latch(7).open(); FutureSpec.ready(latch(8), TestLatch.DefaultTimeout); s.length }
        f4 foreach (_ ⇒ latch(9).open())

        p1 should not be ('completed)
        f4 should not be ('completed)

        p1 complete Success("Hello")

        FutureSpec.ready(latch(7), TestLatch.DefaultTimeout)

        p1 should be('completed)
        f4 should not be ('completed)

        latch(8).open()
        FutureSpec.ready(latch(9), TestLatch.DefaultTimeout)

        FutureSpec.ready(f4, timeout.duration) should be('completed)
      }

      "not deadlock with nested await (ticket 1313)" in {
        val simple = Future(()) map (_ ⇒ Await.result((Future(()) map (_ ⇒ ())), timeout.duration))
        FutureSpec.ready(simple, timeout.duration) should be('completed)

        val l1, l2 = new TestLatch
        val complex = Future(()) map { _ ⇒
          val nested = Future(())
          nested foreach (_ ⇒ l1.open())
          FutureSpec.ready(l1, TestLatch.DefaultTimeout) // make sure nested is completed
          nested foreach (_ ⇒ l2.open())
          FutureSpec.ready(l2, TestLatch.DefaultTimeout)
        }
        FutureSpec.ready(complex, timeout.duration) should be('completed)
      }

      "re-use the same thread for nested futures with batching ExecutionContext" in {
        val failCount = new java.util.concurrent.atomic.AtomicInteger
        val f = Future(()) flatMap { _ ⇒
          val originalThread = Thread.currentThread
          // run some nested futures
          val nested =
            for (i ← 1 to 100)
              yield Future.successful("abc") flatMap { _ ⇒
              if (Thread.currentThread ne originalThread)
                failCount.incrementAndGet
              // another level of nesting
              Future.successful("xyz") map { _ ⇒
                if (Thread.currentThread ne originalThread)
                  failCount.incrementAndGet
              }
            }
          Future.sequence(nested)
        }
        Await.ready(f, timeout.duration)
        // TODO re-enable once we're using the batching dispatcher
        // failCount.get should ===(0)
      }

    }
  }

  def emptyFuture(f: (Future[Any] ⇒ Unit) ⇒ Unit) {
    "not be completed" in { f(_ should not be ('completed)) }
    "not contain a value" in { f(_.value should ===(None)) }
  }

  def futureWithResult(f: ((Future[Any], Any) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future should be('completed)) }
    "contain a value" in { f((future, result) ⇒ future.value should ===(Some(Success(result)))) }
    "return result with 'get'" in { f((future, result) ⇒ Await.result(future, timeout.duration) should ===(result)) }
    "return result with 'Await.result'" in { f((future, result) ⇒ Await.result(future, timeout.duration) should ===(result)) }
    "not timeout" in { f((future, _) ⇒ FutureSpec.ready(future, 0 millis)) }
    "filter result" in {
      f { (future, result) ⇒
        Await.result((future filter (_ ⇒ true)), timeout.duration) should ===(result)
        intercept[java.util.NoSuchElementException] { Await.result((future filter (_ ⇒ false)), timeout.duration) }
      }
    }
    "transform result with map" in { f((future, result) ⇒ Await.result((future map (_.toString.length)), timeout.duration) should ===(result.toString.length)) }
    "compose result with flatMap" in {
      f { (future, result) ⇒
        val r = for (r ← future; p ← Promise.successful("foo").future) yield r.toString + p
        Await.result(r, timeout.duration) should ===(result.toString + "foo")
      }
    }
    "perform action with foreach" in {
      f { (future, result) ⇒
        val p = Promise[Any]()
        future foreach p.success
        Await.result(p.future, timeout.duration) should ===(result)
      }
    }
    "zip properly" in {
      f { (future, result) ⇒
        Await.result(future zip Promise.successful("foo").future, timeout.duration) should ===((result, "foo"))
        (intercept[RuntimeException] { Await.result(future zip Promise.failed(new RuntimeException("ohnoes")).future, timeout.duration) }).getMessage should ===("ohnoes")
      }
    }
    "not recover from exception" in { f((future, result) ⇒ Await.result(future.recover({ case _ ⇒ "pigdog" }), timeout.duration) should ===(result)) }
    "perform action on result" in {
      f { (future, result) ⇒
        val p = Promise[Any]()
        future.onSuccess { case x ⇒ p.success(x) }
        Await.result(p.future, timeout.duration) should ===(result)
      }
    }
    "not project a failure" in { f((future, result) ⇒ (intercept[NoSuchElementException] { Await.result(future.failed, timeout.duration) }).getMessage should ===("Future.failed not completed with a throwable.")) }
    "not perform action on exception" is pending
    "cast using mapTo" in { f((future, result) ⇒ Await.result(future.mapTo[Boolean].recover({ case _: ClassCastException ⇒ false }), timeout.duration) should ===(false)) }
  }

  def futureWithException[E <: Throwable: ClassTag](f: ((Future[Any], String) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future should be('completed)) }
    "contain a value" in {
      f((future, message) ⇒ {
        future.value should be('defined)
        future.value.get should be('failure)
        val Failure(f) = future.value.get
        f.getMessage should ===(message)
      })
    }
    "throw exception with 'get'" in { f((future, message) ⇒ (intercept[java.lang.Exception] { Await.result(future, timeout.duration) }).getMessage should ===(message)) }
    "throw exception with 'Await.result'" in { f((future, message) ⇒ (intercept[java.lang.Exception] { Await.result(future, timeout.duration) }).getMessage should ===(message)) }
    "retain exception with filter" in {
      f { (future, message) ⇒
        (intercept[java.lang.Exception] { Await.result(future filter (_ ⇒ true), timeout.duration) }).getMessage should ===(message)
        (intercept[java.lang.Exception] { Await.result(future filter (_ ⇒ false), timeout.duration) }).getMessage should ===(message)
      }
    }
    "retain exception with map" in { f((future, message) ⇒ (intercept[java.lang.Exception] { Await.result(future map (_.toString.length), timeout.duration) }).getMessage should ===(message)) }
    "retain exception with flatMap" in { f((future, message) ⇒ (intercept[java.lang.Exception] { Await.result(future flatMap (_ ⇒ Promise.successful[Any]("foo").future), timeout.duration) }).getMessage should ===(message)) }
    "not perform action with foreach" is pending

    "zip properly" in {
      f { (future, message) ⇒ (intercept[java.lang.Exception] { Await.result(future zip Promise.successful("foo").future, timeout.duration) }).getMessage should ===(message) }
    }
    "recover from exception" in { f((future, message) ⇒ Await.result(future.recover({ case e if e.getMessage == message ⇒ "pigdog" }), timeout.duration) should ===("pigdog")) }
    "not perform action on result" is pending
    "project a failure" in { f((future, message) ⇒ Await.result(future.failed, timeout.duration).getMessage should ===(message)) }
    "perform action on exception" in {
      f { (future, message) ⇒
        val p = Promise[Any]()
        future.onFailure { case _ ⇒ p.success(message) }
        Await.result(p.future, timeout.duration) should ===(message)
      }
    }
    "always cast successfully using mapTo" in { f((future, message) ⇒ (evaluating { Await.result(future.mapTo[java.lang.Thread], timeout.duration) } should produce[java.lang.Exception]).getMessage should ===(message)) }
  }

  implicit def arbFuture: Arbitrary[Future[Int]] = Arbitrary(for (n ← arbitrary[Int]) yield Future(n))

  implicit def arbFutureAction: Arbitrary[FutureAction] = Arbitrary {

    val genIntAction = for {
      n ← arbitrary[Int]
      a ← Gen.oneOf(IntAdd(n), IntSub(n), IntMul(n), IntDiv(n))
    } yield a

    val genMapAction = genIntAction map (MapAction(_))

    val genFlatMapAction = genIntAction map (FlatMapAction(_))

    Gen.oneOf(genMapAction, genFlatMapAction)

  }

  def checkType[A: ClassTag, B](in: Future[A], reftag: ClassTag[B]): Boolean = implicitly[ClassTag[A]].runtimeClass == reftag.runtimeClass
}
