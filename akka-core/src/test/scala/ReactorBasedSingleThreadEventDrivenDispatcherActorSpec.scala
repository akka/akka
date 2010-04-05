package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers

class ReactorBasedSingleThreadEventDrivenDispatcherActorSpec extends JUnitSuite {
  import Actor.Sender.Self

  private val unit = TimeUnit.MILLISECONDS

  class TestActor extends Actor {
    dispatcher = Dispatchers.newReactorBasedSingleThreadEventDrivenDispatcher(uuid)

    def receive = {
      case "Hello" =>
        reply("World")
      case "Failure" =>
        throw new RuntimeException("expected")
    }
  }

  @Test def shouldSendOneWay = {
    val oneWay = new CountDownLatch(1)
    val actor = new Actor {
      dispatcher = Dispatchers.newReactorBasedSingleThreadEventDrivenDispatcher(uuid)
      def receive = {
        case "OneWay" => oneWay.countDown
      }
    }
    actor.start
    val result = actor ! "OneWay"
    assert(oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test def shouldSendReplySync = {
    val actor = new TestActor
    actor.start
    val result: String = (actor !! ("Hello", 10000)).get
    assert("World" === result)
    actor.stop
  }

  @Test def shouldSendReplyAsync = {
    val actor = new TestActor
    actor.start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test def shouldSendReceiveException = {
    val actor = new TestActor
    actor.start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("expected" === e.getMessage())
    }
    actor.stop
  }
}
