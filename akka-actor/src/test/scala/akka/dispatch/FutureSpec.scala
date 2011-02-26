package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import Actor._
import org.multiverse.api.latches.StandardLatch
import akka.dispatch. {Future, Futures}
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

class FutureSpec extends JUnitSuite {
  import FutureSpec._

  @Test def shouldActorReplyResultThroughExplicitFuture {
    val actor = actorOf[TestActor]
    actor.start
    val future = actor !!! "Hello"
    future.await
    assert(future.result.isDefined)
    assert("World" === future.result.get)
    actor.stop
  }

  @Test def shouldActorReplyExceptionThroughExplicitFuture {
    val actor = actorOf[TestActor]
    actor.start
    val future = actor !!! "Failure"
    future.await
    assert(future.exception.isDefined)
    assert("Expected exception; to test fault-tolerance" === future.exception.get.getMessage)
    actor.stop
  }

  @Test def shouldFutureCompose {
    val actor1 = actorOf[TestActor].start
    val actor2 = actorOf(new Actor { def receive = { case s: String => self reply s.toUpperCase } } ).start
    val future1 = actor1.!!![Any]("Hello").flatMap{ case s: String => actor2.!!![Any](s) }
    val future2 = actor1.!!![Any]("Hello").flatMap{ case s: Int => actor2.!!![Any](s) }
    assert(Some(Right("WORLD")) === future1.await.value)
    assert(Some("scala.MatchError: World") === future2.await.exception.map(_.toString))
    actor1.stop
    actor2.stop
  }

  @Test def shouldFutureForComprehension {
    val actor = actorOf(new Actor {
      def receive = {
        case s: String => self reply s.length
        case i: Int => self reply (i * 2).toString
      }
    }).start

    val future1 = for {
      a: Int    <- actor !!! "Hello" // returns 5
      b: String <- actor !!! a       // returns "10"
      c: String <- actor !!! 7       // returns "14"
    } yield b + "-" + c

    val future2 = for {
      a: Int    <- actor !!! "Hello"
      b: Int    <- actor !!! a
      c: String <- actor !!! 7
    } yield b + "-" + c

    assert(Some(Right("10-14")) === future1.await.value)
    assert(Some("java.lang.ClassCastException: java.lang.String cannot be cast to java.lang.Integer") === future2.await.exception.map(_.toString))
    actor.stop
  }

  @Test def shouldFutureForComprehensionPatternMatch {
    case class Req[T](req: T)
    case class Res[T](res: T)
    val actor = actorOf(new Actor {
      def receive = {
        case Req(s: String) => self reply Res(s.length)
        case Req(i: Int) => self reply Res((i * 2).toString)
      }
    }).start

    val future1 = for {
      Res(a: Int)    <- (actor !!! Req("Hello")): Future[Any]
      Res(b: String) <- (actor !!! Req(a)): Future[Any]
      Res(c: String) <- (actor !!! Req(7)): Future[Any]
    } yield b + "-" + c

    val future2 = for {
      Res(a: Int)    <- (actor !!! Req("Hello")): Future[Any]
      Res(b: Int)    <- (actor !!! Req(a)): Future[Any]
      Res(c: String) <- (actor !!! Req(7)): Future[Any]
    } yield b + "-" + c

    assert(Some(Right("10-14")) === future1.await.value)
    assert(Some("scala.MatchError: Res(10)") === future2.await.exception.map(_.toString))
    actor.stop
  }

  // FIXME: implement Futures.awaitEither, and uncomment these two tests
  @Test def shouldFutureAwaitEitherLeft = {
    val actor1 = actorOf[TestActor].start
    val actor2 = actorOf[TestActor].start
    val future1 = actor1 !!! "Hello"
    val future2 = actor2 !!! "NoReply"
    val result = Futures.awaitEither(future1, future2)
    assert(result.isDefined)
    assert("World" === result.get)
    actor1.stop
    actor2.stop
  }

  @Test def shouldFutureAwaitEitherRight = {
    val actor1 = actorOf[TestActor].start
    val actor2 = actorOf[TestActor].start
    val future1 = actor1 !!! "NoReply"
    val future2 = actor2 !!! "Hello"
    val result = Futures.awaitEither(future1, future2)
    assert(result.isDefined)
    assert("World" === result.get)
    actor1.stop
    actor2.stop
  }

  @Test def shouldFutureAwaitOneLeft = {
    val actor1 = actorOf[TestActor].start
    val actor2 = actorOf[TestActor].start
    val future1 = actor1 !!! "NoReply"
    val future2 = actor2 !!! "Hello"
    val result = Futures.awaitOne(List(future1, future2))
    assert(result.result.isDefined)
    assert("World" === result.result.get)
    actor1.stop
    actor2.stop
  }

  @Test def shouldFutureAwaitOneRight = {
    val actor1 = actorOf[TestActor].start
    val actor2 = actorOf[TestActor].start
    val future1 = actor1 !!! "Hello"
    val future2 = actor2 !!! "NoReply"
    val result = Futures.awaitOne(List(future1, future2))
    assert(result.result.isDefined)
    assert("World" === result.result.get)
    actor1.stop
    actor2.stop
  }

  @Test def shouldFutureAwaitAll = {
    val actor1 = actorOf[TestActor].start
    val actor2 = actorOf[TestActor].start
    val future1 = actor1 !!! "Hello"
    val future2 = actor2 !!! "Hello"
    Futures.awaitAll(List(future1, future2))
    assert(future1.result.isDefined)
    assert("World" === future1.result.get)
    assert(future2.result.isDefined)
    assert("World" === future2.result.get)
    actor1.stop
    actor2.stop
  }

  @Test def shouldFuturesAwaitMapHandleEmptySequence {
    assert(Futures.awaitMap[Nothing,Unit](Nil)(x => ()) === Nil)
  }

  @Test def shouldFuturesAwaitMapHandleNonEmptySequence {
    val latches = (1 to 3) map (_ => new StandardLatch)
    val actors = latches map (latch => actorOf(new TestDelayActor(latch)).start)
    val futures = actors map (actor => (actor.!!![String]("Hello")))
    latches foreach { _.open }

    assert(Futures.awaitMap(futures)(_.result.map(_.length).getOrElse(0)).sum === (latches.size * "World".length))
  }

  @Test def shouldFoldResults {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start
    }
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 200 )) }
    assert(Futures.fold(0)(futures)(_ + _).awaitBlocking.result.get === 45)
  }

  @Test def shouldFoldResultsByComposing {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start
    }
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 200 )) }
    assert(futures.foldLeft(Futures.future(0))((fr, fa) => fr flatMap (r => fa map (_ + r))).awaitBlocking.result.get === 45)
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
      }).start
    }
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 100 )) }
    assert(Futures.fold(0)(futures)(_ + _).awaitBlocking.exception.get.getMessage === "shouldFoldResultsWithException: expected")
  }

  @Test def shouldFoldReturnZeroOnEmptyInput {
    assert(Futures.fold(0)(List[Future[Int]]())(_ + _).awaitBlocking.result.get === 0)
  }

  @Test def shouldReduceResults {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start
    }
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 200 )) }
    assert(Futures.reduce(futures)(_ + _).awaitBlocking.result.get === 45)
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
      }).start
    }
    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, idx * 100 )) }
    assert(Futures.reduce(futures)(_ + _).awaitBlocking.exception.get.getMessage === "shouldFoldResultsWithException: expected")
  }

  @Test(expected = classOf[UnsupportedOperationException]) def shouldReduceThrowIAEOnEmptyInput {
    Futures.reduce(List[Future[Int]]())(_ + _).await.resultOrException
  }

  @Test def resultWithinShouldNotThrowExceptions {
    val actors = (1 to 10).toList map { _ =>
      actorOf(new Actor {
        def receive = { case (add: Int, wait: Int) => Thread.sleep(wait); self reply_? add }
      }).start
    }

    def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) => actor.!!![Int]((idx, if(idx >= 5) 5000 else 0 )) }
    val result = for(f <- futures) yield f.resultWithin(2, TimeUnit.SECONDS)
    val done = result collect { case Some(Right(x)) => x }
    val undone = result collect { case None => None }
    val errors = result collect { case Some(Left(t)) => t }
    assert(done.size === 5)
    assert(undone.size === 5)
    assert(errors.size === 0)
  }

  @Test def receiveShouldExecuteOnComplete {
    val latch = new StandardLatch
    val actor = actorOf[TestActor].start
    actor !!! "Hello" receive { case "World" => latch.open }
    assert(latch.tryAwait(5, TimeUnit.SECONDS))
    actor.stop
  }
}
