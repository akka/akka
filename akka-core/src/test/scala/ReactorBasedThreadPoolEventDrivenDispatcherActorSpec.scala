package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

object ReactorBasedThreadPoolEventDrivenDispatcherActorSpec {
  class TestActor extends Actor {
    self.dispatcher = Dispatchers.newReactorBasedThreadPoolEventDrivenDispatcher(self.uuid)
    def receive = {
      case "Hello" =>
        self.reply("World")
      case "Failure" =>
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class ReactorBasedThreadPoolEventDrivenDispatcherActorSpec extends JUnitSuite {
  import ReactorBasedThreadPoolEventDrivenDispatcherActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldSendOneWay {
    val oneWay = new CountDownLatch(1)
    val actor = actorOf(new Actor {
      self.dispatcher = Dispatchers.newReactorBasedThreadPoolEventDrivenDispatcher(self.uuid)
      def receive = {
        case "OneWay" => oneWay.countDown
      }
    }).start
    val result = actor ! "OneWay"
    assert(oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test def shouldSendReplySync = {
    val actor = actorOf[TestActor].start
    val result = (actor !! ("Hello", 10000)).as[String].get
    assert("World" === result)
    actor.stop
  }

  @Test def shouldSendReplyAsync = {
    val actor = actorOf[TestActor].start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test def shouldSendReceiveException = {
    val actor = actorOf[TestActor].start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("Expected exception; to test fault-tolerance" === e.getMessage())
    }
    actor.stop
  }
}
