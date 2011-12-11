package akka.dispatch

import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.Checkers
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen._
import akka.actor.{ Actor, ActorRef, Status }
import akka.testkit.{ EventFilter, filterEvents, filterException }
import akka.util.duration._
import org.multiverse.api.latches.StandardLatch
import akka.testkit.AkkaSpec
import org.scalatest.junit.JUnitSuite
import java.lang.ArithmeticException
import akka.testkit.DefaultTimeout
import java.util.concurrent.{ TimeoutException, TimeUnit, CountDownLatch }

object FutureSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒ sender ! "World"
      case "Failure" ⇒
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
      case "NoReply" ⇒
    }
  }

  class TestDelayActor(await: StandardLatch) extends Actor {
    def receive = {
      case "Hello"   ⇒ await.await; sender ! "World"
      case "NoReply" ⇒ await.await
      case "Failure" ⇒
        await.await
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class JavaFutureSpec extends JavaFutureTests with JUnitSuite

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FutureSpec extends AkkaSpec with Checkers with BeforeAndAfterAll with DefaultTimeout {
  import FutureSpec._

  "A Promise" when {
    "never completed" must {
      behave like emptyFuture(_(Promise()))
      "return supplied value on timeout" in {
        val timedOut = new KeptPromise[String](Right("Timedout"))
        val promise = Promise[String]() orElse timedOut
        promise.get must be("Timedout")
      }
    }
    "completed with a result" must {
      val result = "test value"
      val future = Promise[String]().complete(Right(result))
      behave like futureWithResult(_(future, result))
    }
    "completed with an exception" must {
      val message = "Expected Exception"
      val future = Promise[String]().complete(Left(new RuntimeException(message)))
      behave like futureWithException[RuntimeException](_(future, message))
    }
  }

  "A Future" when {
    "awaiting a result" that {
      "is not completed" must {
        behave like emptyFuture { test ⇒
          val latch = new StandardLatch
          val result = "test value"
          val future = Future {
            latch.await
            result
          }
          test(future)
          latch.open
          Block.on(future, timeout.duration)
        }
      }
      "is completed" must {
        behave like futureWithResult { test ⇒
          val latch = new StandardLatch
          val result = "test value"
          val future = Future {
            latch.await
            result
          }
          latch.open
          Block.on(future, timeout.duration)
          test(future, result)
        }
      }
      "has actions applied" must {
        "pass checks" in {
          filterException[ArithmeticException] {
            check({ (future: Future[Int], actions: List[FutureAction]) ⇒
              val result = (future /: actions)(_ /: _)
              val expected = (Block.on(future, timeout.duration).value.get /: actions)(_ /: _)
              ((Block.on(result, timeout.duration).value.get, expected) match {
                case (Right(a), Right(b))                           ⇒ a == b
                case (Left(a), Left(b)) if a.toString == b.toString ⇒ true
                case (Left(a), Left(b)) if a.getStackTrace.isEmpty || b.getStackTrace.isEmpty ⇒
                  a.getClass.toString == b.getClass.toString
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
          val actor = system.actorOf[TestActor]
          val future = actor ? "Hello"
          Block.on(future, timeout.duration)
          test(future, "World")
          actor.stop()
        }
      }
      "throws an exception" must {
        behave like futureWithException[RuntimeException] { test ⇒
          filterException[RuntimeException] {
            val actor = system.actorOf[TestActor]
            val future = actor ? "Failure"
            Block.on(future, timeout.duration)
            test(future, "Expected exception; to test fault-tolerance")
            actor.stop()
          }
        }
      }
    }

    "using flatMap with an Actor" that {
      "will return a result" must {
        behave like futureWithResult { test ⇒
          val actor1 = system.actorOf[TestActor]
          val actor2 = system.actorOf(new Actor { def receive = { case s: String ⇒ sender ! s.toUpperCase } })
          val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
          Block.on(future, timeout.duration)
          test(future, "WORLD")
          actor1.stop()
          actor2.stop()
        }
      }
      "will throw an exception" must {
        behave like futureWithException[ArithmeticException] { test ⇒
          filterException[ArithmeticException] {
            val actor1 = system.actorOf[TestActor]
            val actor2 = system.actorOf(new Actor { def receive = { case s: String ⇒ sender ! Status.Failure(new ArithmeticException("/ by zero")) } })
            val future = actor1 ? "Hello" flatMap { case s: String ⇒ actor2 ? s }
            Block.on(future, timeout.duration)
            test(future, "/ by zero")
            actor1.stop()
            actor2.stop()
          }
        }
      }
      "will throw a MatchError when matching wrong type" must {
        behave like futureWithException[MatchError] { test ⇒
          filterException[MatchError] {
            val actor1 = system.actorOf[TestActor]
            val actor2 = system.actorOf(new Actor { def receive = { case s: String ⇒ sender ! s.toUpperCase } })
            val future = actor1 ? "Hello" flatMap { case i: Int ⇒ actor2 ? i }
            Block.on(future, timeout.duration)
            test(future, "World (of class java.lang.String)")
            actor1.stop()
            actor2.stop()
          }
        }
      }
    }

    "being tested" must {

      "compose with for-comprehensions" in {
        filterException[ClassCastException] {
          val actor = system.actorOf(new Actor {
            def receive = {
              case s: String ⇒ sender ! s.length
              case i: Int    ⇒ sender ! (i * 2).toString
            }
          })

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

          future1.get must be("10-14")
          assert(checkType(future1, manifest[String]))
          intercept[ClassCastException] { future2.get }
          actor.stop()
        }
      }

      "support pattern matching within a for-comprehension" in {
        filterException[MatchError] {
          case class Req[T](req: T)
          case class Res[T](res: T)
          val actor = system.actorOf(new Actor {
            def receive = {
              case Req(s: String) ⇒ sender ! Res(s.length)
              case Req(i: Int)    ⇒ sender ! Res((i * 2).toString)
            }
          })

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

          future1.get must be("10-14")
          intercept[MatchError] { future2.get }
          actor.stop()
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

          val actor = system.actorOf[TestActor]

          val future8 = actor ? "Failure"
          val future9 = actor ? "Failure" recover {
            case e: RuntimeException ⇒ "FAIL!"
          }
          val future10 = actor ? "Hello" recover {
            case e: RuntimeException ⇒ "FAIL!"
          }
          val future11 = actor ? "Failure" recover { case _ ⇒ "Oops!" }

          future1.get must be(5)
          intercept[ArithmeticException] { future2.get }
          intercept[ArithmeticException] { future3.get }
          future4.get must be("5")
          future5.get must be("0")
          intercept[ArithmeticException] { future6.get }
          future7.get must be("You got ERROR")
          intercept[RuntimeException] { future8.get }
          future9.get must be("FAIL!")
          future10.get must be("World")
          future11.get must be("Oops!")

          actor.stop()
        }
      }

      "firstCompletedOf" in {
        val futures = Vector.fill[Future[Int]](10)(Promise[Int]()) :+ new KeptPromise[Int](Right(5))
        Future.firstCompletedOf(futures).get must be(5)
      }

      "find" in {
        val futures = for (i ← 1 to 10) yield Future { i }
        val result = Future.find[Int](futures)(_ == 3)
        result.get must be(Some(3))

        val notFound = Future.find[Int](futures)(_ == 11)
        notFound.get must be(None)
      }

      "fold" in {
        val actors = (1 to 10).toList map { _ ⇒
          system.actorOf(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); sender.tell(add) }
          })
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200), timeout).mapTo[Int] }
        Block.sync(Future.fold(futures)(0)(_ + _), timeout millis) must be(45)
      }

      "fold by composing" in {
        val actors = (1 to 10).toList map { _ ⇒
          system.actorOf(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); sender.tell(add) }
          })
        }
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200), 10000).mapTo[Int] }
        futures.foldLeft(Future(0))((fr, fa) ⇒ for (r ← fr; a ← fa) yield (r + a)).get must be(45)
      }

      "fold with an exception" in {
        filterException[IllegalArgumentException] {
          val actors = (1 to 10).toList map { _ ⇒
            system.actorOf(new Actor {
              def receive = {
                case (add: Int, wait: Int) ⇒
                  Thread.sleep(wait)
                  if (add == 6) sender ! Status.Failure(new IllegalArgumentException("shouldFoldResultsWithException: expected"))
                  else sender.tell(add)
              }
            })
          }
          val timeout = 10000
          def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 100), timeout).mapTo[Int] }
          intercept[Throwable] { Block.sync(Future.fold(futures)(0)(_ + _), timeout millis) }.getMessage must be("shouldFoldResultsWithException: expected")
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
          val result = Block.sync(f.mapTo[ArrayBuffer[Int]], 10000 millis).sum

          assert(result === 250500)
        }

        (1 to 100) foreach test //Make sure it tries to provoke the problem
      }

      "return zero value if folding empty list" in {
        Future.fold(List[Future[Int]]())(0)(_ + _).get must be(0)
      }

      "shouldReduceResults" in {
        val actors = (1 to 10).toList map { _ ⇒
          system.actorOf(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); sender.tell(add) }
          })
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200), timeout).mapTo[Int] }
        assert(Block.sync(Future.reduce(futures)(_ + _), timeout millis) === 45)
      }

      "shouldReduceResultsWithException" in {
        filterException[IllegalArgumentException] {
          val actors = (1 to 10).toList map { _ ⇒
            system.actorOf(new Actor {
              def receive = {
                case (add: Int, wait: Int) ⇒
                  Thread.sleep(wait)
                  if (add == 6) sender ! Status.Failure(new IllegalArgumentException("shouldFoldResultsWithException: expected"))
                  else sender.tell(add)
              }
            })
          }
          val timeout = 10000
          def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 100), timeout).mapTo[Int] }
          intercept[Throwable] { Block.sync(Future.reduce(futures)(_ + _), timeout millis) }.getMessage must be === "shouldFoldResultsWithException: expected"
        }
      }

      "shouldReduceThrowIAEOnEmptyInput" in {
        filterException[IllegalArgumentException] {
          intercept[UnsupportedOperationException] { Block.sync(Future.reduce(List[Future[Int]]())(_ + _), timeout.duration) }
        }
      }

      "receiveShouldExecuteOnComplete" in {
        val latch = new StandardLatch
        val actor = system.actorOf[TestActor]
        actor ? "Hello" onResult { case "World" ⇒ latch.open }
        assert(latch.tryAwait(5, TimeUnit.SECONDS))
        actor.stop()
      }

      "shouldTraverseFutures" in {
        val oddActor = system.actorOf(new Actor {
          var counter = 1
          def receive = {
            case 'GetNext ⇒
              sender ! counter
              counter += 2
          }
        })

        val oddFutures = List.fill(100)(oddActor ? 'GetNext mapTo manifest[Int])
        assert(Future.sequence(oddFutures).get.sum === 10000)
        oddActor.stop()

        val list = (1 to 100).toList
        assert(Future.traverse(list)(x ⇒ Future(x * 2 - 1)).get.sum === 10000)
      }

      "shouldHandleThrowables" in {
        class ThrowableTest(m: String) extends Throwable(m)

        filterException[ThrowableTest] {
          val f1 = Future[Any] { throw new ThrowableTest("test") }
          intercept[ThrowableTest] { Block.sync(f1, timeout.duration) }

          val latch = new StandardLatch
          val f2 = Future { latch.tryAwait(5, TimeUnit.SECONDS); "success" }
          f2 foreach (_ ⇒ throw new ThrowableTest("dispatcher foreach"))
          f2 onResult { case _ ⇒ throw new ThrowableTest("dispatcher receive") }
          val f3 = f2 map (s ⇒ s.toUpperCase)
          latch.open
          assert(Block.sync(f2, timeout.duration) === "success")
          f2 foreach (_ ⇒ throw new ThrowableTest("current thread foreach"))
          f2 onResult { case _ ⇒ throw new ThrowableTest("current thread receive") }
          assert(Block.sync(f3, timeout.duration) === "SUCCESS")
        }
      }

      "shouldBlockUntilResult" in {
        val latch = new StandardLatch

        val f = Future { latch.await; 5 }
        val f2 = Future { Block.sync(f, timeout.duration) + 5 }

        intercept[TimeoutException](Block.on(f2, 100 millis))
        latch.open
        assert(Block.sync(f2, timeout.duration) === 10)

        val f3 = Future { Thread.sleep(100); 5 }
        filterException[TimeoutException] { intercept[TimeoutException] { Block.on(f3, 0 millis) } }
      }

      "futureComposingWithContinuations" in {
        import Future.flow

        val actor = system.actorOf[TestActor]

        val x = Future("Hello")
        val y = x flatMap (actor ? _) mapTo manifest[String]

        val r = flow(x() + " " + y() + "!")

        assert(r.get === "Hello World!")

        actor.stop
      }

      "futureComposingWithContinuationsFailureDivideZero" in {
        filterException[ArithmeticException] {
          import Future.flow

          val x = Future("Hello")
          val y = x map (_.length)

          val r = flow(x() + " " + y.map(_ / 0).map(_.toString).apply, 100)

          intercept[java.lang.ArithmeticException](r.get)
        }
      }

      "futureComposingWithContinuationsFailureCastInt" in {
        filterException[ClassCastException] {
          import Future.flow

          val actor = system.actorOf[TestActor]

          val x = Future(3)
          val y = (actor ? "Hello").mapTo[Int]

          val r = flow(x() + y(), 100)

          intercept[ClassCastException](r.get)
        }
      }

      "futureComposingWithContinuationsFailureCastNothing" in {
        filterException[ClassCastException] {
          import Future.flow

          val actor = system.actorOf[TestActor]

          val x = Future("Hello")
          val y = actor ? "Hello" mapTo manifest[Nothing]

          val r = flow(x() + y())

          intercept[ClassCastException](r.get)
        }
      }

      "futureCompletingWithContinuations" in {
        import Future.flow

        val x, y, z = Promise[Int]()
        val ly, lz = new StandardLatch

        val result = flow {
          y completeWith x
          ly.open // not within continuation

          z << x
          lz.open // within continuation, will wait for 'z' to complete
          z() + y()
        }

        assert(ly.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))
        assert(!lz.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))

        flow { x << 5 }

        assert(y.get === 5)
        assert(z.get === 5)
        assert(lz.isOpen)
        assert(result.get === 10)

        val a, b, c = Promise[Int]()

        val result2 = flow {
          val n = (a << c).result.get + 10
          b << (c() - 2)
          a() + n * b()
        }

        c completeWith Future(5)

        assert(a.get === 5)
        assert(b.get === 3)
        assert(result2.get === 50)
      }

      "futureDataFlowShouldEmulateBlocking1" in {
        import Future.flow

        val one, two = Promise[Int]()
        val simpleResult = flow {
          one() + two()
        }

        assert(List(one, two, simpleResult).forall(_.isCompleted == false))

        flow { one << 1 }

        Block.on(one, 1 minute)

        assert(one.isCompleted)
        assert(List(two, simpleResult).forall(_.isCompleted == false))

        flow { two << 9 }

        Block.on(two, 1 minute)

        assert(List(one, two).forall(_.isCompleted == true))
        assert(simpleResult.get === 10)

      }

      "futureDataFlowShouldEmulateBlocking2" in {
        import Future.flow
        val x1, x2, y1, y2 = Promise[Int]()
        val lx, ly, lz = new StandardLatch
        val result = flow {
          lx.open()
          x1 << y1
          ly.open()
          x2 << y2
          lz.open()
          x1() + x2()
        }
        assert(lx.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(!ly.isOpen)
        assert(!lz.isOpen)
        assert(List(x1, x2, y1, y2).forall(_.isCompleted == false))

        flow { y1 << 1 } // When this is set, it should cascade down the line

        assert(ly.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(Block.sync(x1, 1 minute) === 1)
        assert(!lz.isOpen)

        flow { y2 << 9 } // When this is set, it should cascade down the line

        assert(lz.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(Block.sync(x2, 1 minute) === 9)

        assert(List(x1, x2, y1, y2).forall(_.isCompleted))

        assert(Block.sync(result, 1 minute) === 10)
      }

      "dataFlowAPIshouldbeSlick" in {
        import Future.flow

        val i1, i2, s1, s2 = new StandardLatch

        val callService1 = Future { i1.open; s1.awaitUninterruptible; 1 }
        val callService2 = Future { i2.open; s2.awaitUninterruptible; 9 }

        val result = flow { callService1() + callService2() }

        assert(!s1.isOpen)
        assert(!s2.isOpen)
        assert(!result.isCompleted)
        assert(i1.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(i2.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        s1.open
        s2.open
        assert(result.get === 10)
      }

      "futureCompletingWithContinuationsFailure" in {
        filterException[ArithmeticException] {
          import Future.flow

          val x, y, z = Promise[Int]()
          val ly, lz = new StandardLatch

          val result = flow {
            y << x
            ly.open
            val oops = 1 / 0
            z << x
            lz.open
            z() + y() + oops
          }

          assert(!ly.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))
          assert(!lz.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))

          flow { x << 5 }

          assert(y.get === 5)
          intercept[java.lang.ArithmeticException](result.get)
          assert(z.value === None)
          assert(!lz.isOpen)
        }
      }

      "futureContinuationsShouldNotBlock" in {
        import Future.flow

        val latch = new StandardLatch
        val future = Future {
          latch.await
          "Hello"
        }

        val result = flow {
          Some(future()).filter(_ == "Hello")
        }

        assert(!result.isCompleted)

        latch.open

        assert(result.get === Some("Hello"))
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

        Block.sync(rString, timeout.duration)
        Block.sync(rInt, timeout.duration)
      }

      "futureFlowSimpleAssign" in {
        import Future.flow

        val x, y, z = Promise[Int]()

        flow {
          z << x() + y()
        }
        flow { x << 40 }
        flow { y << 2 }

        assert(z.get === 42)
      }

      "futureFlowLoops" in {
        import Future.flow
        import akka.util.cps._

        val count = 1000

        val promises = List.fill(count)(Promise[Int]())

        flow {
          var i = 0
          val iter = promises.iterator
          whileC(iter.hasNext) {
            iter.next << i
            i += 1
          }
        }

        var i = 0
        promises foreach { p ⇒
          assert(p.get === i)
          i += 1
        }

        assert(i === count)

      }

      "run callbacks async" in {
        val latch = Vector.fill(10)(new StandardLatch)

        val f1 = Future { latch(0).open; latch(1).await; "Hello" }
        val f2 = f1 map { s ⇒ latch(2).open; latch(3).await; s.length }
        f2 foreach (_ ⇒ latch(4).open)

        latch(0).await

        f1 must not be ('completed)
        f2 must not be ('completed)

        latch(1).open
        latch(2).await

        f1 must be('completed)
        f2 must not be ('completed)

        val f3 = f1 map { s ⇒ latch(5).open; latch(6).await; s.length * 2 }
        f3 foreach (_ ⇒ latch(3).open)

        latch(5).await

        f3 must not be ('completed)

        latch(6).open
        latch(4).await

        f2 must be('completed)
        f3 must be('completed)

        val p1 = Promise[String]()
        val f4 = p1 map { s ⇒ latch(7).open; latch(8).await; s.length }
        f4 foreach (_ ⇒ latch(9).open)

        p1 must not be ('completed)
        f4 must not be ('completed)

        p1 complete Right("Hello")

        latch(7).await

        p1 must be('completed)
        f4 must not be ('completed)

        latch(8).open
        latch(9).await

        Block.on(f4, timeout.duration) must be('completed)
      }

      "should not deadlock with nested await (ticket 1313)" in {
        val simple = Future() map (_ ⇒ (Future(()) map (_ ⇒ ())).get)
        Block.on(simple, timeout.duration) must be('completed)

        val l1, l2 = new StandardLatch
        val complex = Future() map { _ ⇒
          Future.blocking(system.dispatcher)
          val nested = Future(())
          nested foreach (_ ⇒ l1.open)
          l1.await // make sure nested is completed
          nested foreach (_ ⇒ l2.open)
          l2.await
        }
        Block.on(complex, timeout.duration) must be('completed)
      }
    }
  }

  def emptyFuture(f: (Future[Any] ⇒ Unit) ⇒ Unit) {
    "not be completed" in { f(_ must not be ('completed)) }
    "not contain a value" in { f(_.value must be(None)) }
    "not contain a result" in { f(_.result must be(None)) }
    "not contain an exception" in { f(_.exception must be(None)) }
  }

  def futureWithResult(f: ((Future[Any], Any) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future must be('completed)) }
    "contain a value" in { f((future, result) ⇒ future.value must be(Some(Right(result)))) }
    "contain a result" in { f((future, result) ⇒ future.result must be(Some(result))) }
    "not contain an exception" in { f((future, _) ⇒ future.exception must be(None)) }
    "return result with 'get'" in { f((future, result) ⇒ future.get must be(result)) }
    "return result with 'Block.sync'" in { f((future, result) ⇒ Block.sync(future, timeout.duration) must be(result)) }
    "not timeout" in { f((future, _) ⇒ Block.on(future, 0 millis)) }
    "filter result" in {
      f { (future, result) ⇒
        (future filter (_ ⇒ true)).get must be(result)
        (evaluating { (future filter (_ ⇒ false)).get } must produce[MatchError]).getMessage must startWith(result.toString)
      }
    }
    "transform result with map" in { f((future, result) ⇒ (future map (_.toString.length)).get must be(result.toString.length)) }
    "compose result with flatMap" is pending
    "perform action with foreach" is pending
    "match result with collect" is pending
    "not recover from exception" is pending
    "perform action on result" is pending
    "not perform action on exception" is pending
    "cast using mapTo" is pending
  }

  def futureWithException[E <: Throwable: Manifest](f: ((Future[Any], String) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future must be('completed)) }
    "contain a value" in { f((future, _) ⇒ future.value must be('defined)) }
    "not contain a result" in { f((future, _) ⇒ future.result must be(None)) }
    "contain an exception" in { f((future, message) ⇒ future.exception.get.getMessage must be(message)) }
    "throw exception with 'get'" in { f((future, message) ⇒ (evaluating { future.get } must produce[E]).getMessage must be(message)) }
    "throw exception with 'Block.sync'" in { f((future, message) ⇒ (evaluating { Block.sync(future, timeout.duration) } must produce[E]).getMessage must be(message)) }
    "retain exception with filter" in {
      f { (future, message) ⇒
        (evaluating { (future filter (_ ⇒ true)).get } must produce[E]).getMessage must be(message)
        (evaluating { (future filter (_ ⇒ false)).get } must produce[E]).getMessage must be(message)
      }
    }
    "retain exception with map" in { f((future, message) ⇒ (evaluating { (future map (_.toString.length)).get } must produce[E]).getMessage must be(message)) }
    "retain exception with flatMap" is pending
    "not perform action with foreach" is pending
    "retain exception with collect" is pending
    "recover from exception" is pending
    "not perform action on result" is pending
    "perform action on exception" is pending
    "always cast successfully using mapTo" is pending
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
      case Right(r) ⇒ try { Right(action(r)) } catch { case e: RuntimeException ⇒ Left(e) }
    }
    def /:(that: Future[Int]): Future[Int] = that map (action(_))
  }

  case class FlatMapAction(action: IntAction) extends FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int] = that match {
      case Left(e)  ⇒ that
      case Right(r) ⇒ try { Right(action(r)) } catch { case e: RuntimeException ⇒ Left(e) }
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
