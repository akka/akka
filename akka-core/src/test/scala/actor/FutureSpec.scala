package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.dispatch.Futures
import Actor._

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
    assert("Expected exception; to test fault-tolerance" === future.exception.get._2.getMessage)
    actor.stop
  }

  /*
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
  */
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
}
