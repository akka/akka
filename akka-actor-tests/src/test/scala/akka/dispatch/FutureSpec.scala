package akka.dispatch

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.actor.{ Actor, ActorRef }
import Actor._
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent. {TimeUnit, CountDownLatch}

object FutureSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello" =>
        self.reply("World")
      case "NoReply" => {}
      case "Failure" =>
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  class TestDelayActor(await: StandardLatch) extends Actor {
    def receive = {
      case "Hello" =>
        await.await
        self.reply("World")
      case "NoReply" => { await.await }
      case "Failure" =>
        await.await
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class JavaFutureSpec extends JavaFutureTests with JUnitSuite

class FutureSpec extends JUnitSuite {
  import FutureSpec._

  @Test def shouldActorReplyResultThroughExplicitFuture {
    val actor = actorOf[TestActor]
    actor.start()
    val future = actor !!! "Hello"
    future.await
    assert(future.result.isDefined)
    assert("World" === future.result.get)
    actor.stop()
  }

  @Test def shouldActorReplyExceptionThroughExplicitFuture {
    val actor = actorOf[TestActor]
    actor.start()
    val future = actor !!! "Failure"
    future.await
    assert(future.exception.isDefined)
    assert("Expected exception; to test fault-tolerance" === future.exception.get.getMessage)
    actor.stop()
  }

  @Test def shouldFutureCompose {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf(new Actor { def receive = { case s: String => self reply s.toUpperCase } } ).start()
    val future1 = actor1 !!! "Hello" flatMap ((s: String) => actor2 !!! s)
    val future2 = actor1 !!! "Hello" flatMap (actor2 !!! (_: String))
    val future3 = actor1 !!! "Hello" flatMap (actor2 !!! (_: Int))
    assert(Some(Right("WORLD")) === future1.await.value)
    assert(Some(Right("WORLD")) === future2.await.value)
    intercept[ClassCastException] { future3.await.resultOrException }
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFutureComposePatternMatch {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf(new Actor { def receive = { case s: String => self reply s.toUpperCase } } ).start()
    val future1 = actor1 !!! "Hello" collect { case (s: String) => s } flatMap (actor2 !!! _)
    val future2 = actor1 !!! "Hello" collect { case (n: Int) => n } flatMap (actor2 !!! _)
    assert(Some(Right("WORLD")) === future1.await.value)
    intercept[MatchError] { future2.await.resultOrException }
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFutureForComprehension {
    val actor = actorOf(new Actor {
      def receive = {
        case s: String => self reply s.length
        case i: Int => self reply (i * 2).toString
      }
    }).start()

    val future0 = actor !!! "Hello"

    val future1 = for {
      a: Int    <- future0           // returns 5
      b: String <- actor !!! a       // returns "10"
      c: String <- actor !!! 7       // returns "14"
    } yield b + "-" + c

    val future2 = for {
      a: Int    <- future0
      b: Int    <- actor !!! a
      c: String <- actor !!! 7
    } yield b + "-" + c

    assert(Some(Right("10-14")) === future1.await.value)
    intercept[ClassCastException] { future2.await.resultOrException }
    actor.stop()
  }

  @Test def shouldFutureForComprehensionPatternMatch {
    case class Req[T](req: T)
    case class Res[T](res: T)
    val actor = actorOf(new Actor {
      def receive = {
        case Req(s: String) => self reply Res(s.length)
        case Req(i: Int) => self reply Res((i * 2).toString)
      }
    }).start()

    val future1 = for {
      a <- actor !!! Req("Hello") collect { case Res(x: Int) => x }
      b <- actor !!! Req(a) collect { case Res(x: String) => x }
      c <- actor !!! Req(7) collect { case Res(x: String) => x }
    } yield b + "-" + c

    val future2 = for {
      a <- actor !!! Req("Hello") collect { case Res(x: Int) => x }
      b <- actor !!! Req(a) collect { case Res(x: Int) => x }
      c <- actor !!! Req(7) collect { case Res(x: String) => x }
    } yield b + "-" + c

    assert(Some(Right("10-14")) === future1.await.value)
    intercept[MatchError] { future2.await.resultOrException }
    actor.stop()
  }

  @Test def shouldFutureAwaitEitherLeft = {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf[TestActor].start()
    val future1 = actor1 !!! "Hello"
    val future2 = actor2 !!! "NoReply"
    val result = Futures.awaitEither(future1, future2)
    assert(result.isDefined)
    assert("World" === result.get)
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFutureAwaitEitherRight = {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf[TestActor].start()
    val future1 = actor1 !!! "NoReply"
    val future2 = actor2 !!! "Hello"
    val result = Futures.awaitEither(future1, future2)
    assert(result.isDefined)
    assert("World" === result.get)
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFutureAwaitOneLeft = {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf[TestActor].start()
    val future1 = actor1 !!! "NoReply"
    val future2 = actor2 !!! "Hello"
    val result = Futures.awaitOne(List(future1, future2))
    assert(result.result.isDefined)
    assert("World" === result.result.get)
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFutureAwaitOneRight = {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf[TestActor].start()
    val future1 = actor1 !!! "Hello"
    val future2 = actor2 !!! "NoReply"
    val result = Futures.awaitOne(List(future1, future2))
    assert(result.result.isDefined)
    assert("World" === result.result.get)
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFutureAwaitAll = {
    val actor1 = actorOf[TestActor].start()
    val actor2 = actorOf[TestActor].start()
    val future1 = actor1 !!! "Hello"
    val future2 = actor2 !!! "Hello"
    Futures.awaitAll(List(future1, future2))
    assert(future1.result.isDefined)
    assert("World" === future1.result.get)
    assert(future2.result.isDefined)
    assert("World" === future2.result.get)
    actor1.stop()
    actor2.stop()
  }

  @Test def shouldFuturesAwaitMapHandleEmptySequence {
    assert(Futures.awaitMap[Nothing,Unit](Nil)(x => ()) === Nil)
  }

  @Test def shouldFuturesAwaitMapHandleNonEmptySequence {
    val latches = (1 to 3) map (_ => new StandardLatch)
    val actors = latches map (latch => actorOf(new TestDelayActor(latch)).start())
    val futures = actors map (actor => (actor.!!![String]("Hello")))
    latches foreach { _.open }

    assert(Futures.awaitMap(futures)(_.result.map(_.length).getOrElse(0)).sum === (latches.size * "World".length))
  }

  @Test def shouldFoldResults {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start()
    }
    val timeout = 10000
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 200 ), timeout) }
    assert(Futures.fold(0, timeout)(futures)(_ + _).await.result.get === 45)
  }

  @Test def shouldFoldResultsByComposing {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start()
    }
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 200 ), 10000) }
    assert(futures.foldLeft(Future(0))((fr, fa) => for (r <- fr; a <- fa) yield (r + a)).get === 45)
  }

  @Test def shouldFoldResultsWithException {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = {
          case (add: Int, wait: Int) =>
            Thread.sleep(wait)
            if (add == 6) throw new IllegalArgumentException("shouldFoldResultsWithException: expected")
            self reply_? add
        }
      }).start()
    }
    val timeout = 10000
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 100 ), timeout) }
    assert(Futures.fold(0, timeout)(futures)(_ + _).await.exception.get.getMessage === "shouldFoldResultsWithException: expected")
  }

  @Test def shouldFoldReturnZeroOnEmptyInput {
    assert(Futures.fold(0)(List[Future[Int]]())(_ + _).get === 0)
  }

  @Test def shouldReduceResults {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start()
    }
    val timeout = 10000
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 200 ), timeout) }
    assert(Futures.reduce(futures, timeout)(_ + _).get === 45)
  }

  @Test def shouldReduceResultsWithException {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = {
          case (add: Int, wait: Int) =>
            Thread.sleep(wait)
            if (add == 6) throw new IllegalArgumentException("shouldFoldResultsWithException: expected")
            self reply_? add
        }
      }).start()
    }
    val timeout = 10000
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 100 ), timeout) }
    assert(Futures.reduce(futures, timeout)(_ + _).await.exception.get.getMessage === "shouldFoldResultsWithException: expected")
  }

  @Test(expected = classOf[UnsupportedOperationException]) def shouldReduceThrowIAEOnEmptyInput {
    Futures.reduce(List[Future[Int]]())(_ + _).await.resultOrException
  }

  @Test def receiveShouldExecuteOnComplete {
    val latch = new StandardLatch
    val actor = actorOf[TestActor].start()
    actor !!! "Hello" receive { case "World" => latch.open }
    assert(latch.tryAwait(5, TimeUnit.SECONDS))
    actor.stop()
  }

  @Test def shouldTraverseFutures {
    val oddActor = actorOf(new Actor {
      var counter = 1
      def receive = {
        case 'GetNext =>
          self reply counter
          counter += 2
      }
    }).start()

    val oddFutures: List[Future[Int]] = List.fill(100)(oddActor !!! 'GetNext)
    assert(Future.sequence(oddFutures).get.sum === 10000)
    oddActor.stop()

    val list = (1 to 100).toList
    assert(Future.traverse(list)(x => Future(x * 2 - 1)).get.sum === 10000)
  }

  @Test def shouldHandleThrowables {
    class ThrowableTest(m: String) extends Throwable(m)

    val f1 = Future { throw new ThrowableTest("test") }
    f1.await
    intercept[ThrowableTest] { f1.resultOrException }

    val latch = new StandardLatch
    val f2 = Future { latch.tryAwait(5, TimeUnit.SECONDS); "success" }
    f2 foreach (_ => throw new ThrowableTest("dispatcher foreach"))
    f2 receive { case _ => throw new ThrowableTest("dispatcher receive") }
    val f3 = f2 map (s => s.toUpperCase)
    latch.open
    f2.await
    assert(f2.resultOrException === Some("success"))
    f2 foreach (_ => throw new ThrowableTest("current thread foreach"))
    f2 receive { case _ => throw new ThrowableTest("current thread receive") }
    f3.await
    assert(f3.resultOrException === Some("SUCCESS"))

    // make sure all futures are completed in dispatcher
    assert(Dispatchers.defaultGlobalDispatcher.pendingFutures === 0)
  }

  @Test def shouldBlockUntilResult {
    val latch = new StandardLatch

    val f = Future({ latch.await; 5})
    val f2 = Future({ f() + 5 })

    assert(f2.resultOrException === None)
    latch.open
    assert(f2() === 10)

    val f3 = Future({ Thread.sleep(100); 5}, 10)
    intercept[FutureTimeoutException] {
      f3()
    }
    Thread.sleep(100)

    // make sure all futures are completed in dispatcher
    assert(Dispatchers.defaultGlobalDispatcher.pendingFutures === 0)
  }

  @Test def shouldNotAddOrRunCallbacksAfterFailureToBeCompletedBeforeExpiry {
    val latch = new StandardLatch
    val f = new DefaultCompletableFuture[Int](0)
    Thread.sleep(25)
    f.onComplete( _ => latch.open ) //Shouldn't throw any exception here

    assert(f.isExpired) //Should be expired

    f.complete(Right(1)) //Shouldn't complete the Future since it is expired

    assert(f.value.isEmpty) //Shouldn't be completed
    assert(!latch.isOpen) //Shouldn't run the listener
  }

  @Test def lesslessIsMore {
    import akka.actor.Actor.spawn
    val dataflowVar, dataflowVar2 = new DefaultCompletableFuture[Int](Long.MaxValue)
    val begin, end = new StandardLatch
    spawn {
      begin.await
      dataflowVar2 << dataflowVar
      end.open
    }

    spawn {
      dataflowVar << 5
    }
    begin.open
    end.await
    assert(dataflowVar2() === 5)
    assert(dataflowVar.get === 5)
  }

  @Test def ticket812FutureDispatchCleanup {
    val dispatcher = implicitly[MessageDispatcher]
    assert(dispatcher.pendingFutures === 0)
    val future = Future({Thread.sleep(100);"Done"}, 10)
    intercept[FutureTimeoutException] { future.await }
    assert(dispatcher.pendingFutures === 1)
    Thread.sleep(100)
    assert(dispatcher.pendingFutures === 0)
  }
}
