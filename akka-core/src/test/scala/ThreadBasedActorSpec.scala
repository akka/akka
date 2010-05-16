package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

object ThreadBasedActorSpec {
  class TestActor extends Actor {
    self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)
    
    def receive = {
      case "Hello" =>
        self.reply("World")
      case "Failure" =>
        throw new RuntimeException("expected")
    }
  }  
}

class ThreadBasedActorSpec extends JUnitSuite {
  import ThreadBasedActorSpec._
  
  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldSendOneWay  {
    var oneWay = new CountDownLatch(1)
    val actor = newActor(() => new Actor {
      self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)
      def receive = {
        case "OneWay" => oneWay.countDown
      }
    }).start
    val result = actor ! "OneWay"
    assert(oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test def shouldSendReplySync  {
    val actor = newActor[TestActor].start
    val result: String = (actor !! ("Hello", 10000)).get
    assert("World" === result)
    actor.stop
  }

  @Test def shouldSendReplyAsync  {
    val actor = newActor[TestActor].start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test def shouldSendReceiveException  {
    val actor = newActor[TestActor].start
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
