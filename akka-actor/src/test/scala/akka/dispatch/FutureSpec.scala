package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.dispatch.Futures
import Actor._
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent.CountDownLatch

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

  @Test def shouldFutureMapBeDeferred {
    val latch = new StandardLatch
    val actor1 = actorOf(new TestDelayActor(latch)).start

    val mappedFuture = (actor1.!!![String]("Hello")).map(x => 5)
    assert(mappedFuture.isCompleted === false)
    assert(mappedFuture.isExpired === false)
    latch.open
    mappedFuture.await
    assert(mappedFuture.isCompleted === true)
    assert(mappedFuture.isExpired === false)
    assert(mappedFuture.result === Some(5))
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
}
