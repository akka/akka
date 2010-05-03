package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

object ExecutorBasedEventDrivenDispatcherActorSpec {
  class TestActor extends Actor {
    dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(uuid)
    def receive = {
      case "Hello" =>
        reply("World")
      case "Failure" =>
        throw new RuntimeException("expected")
    }
  }

  object OneWayTestActor { 
    val oneWay = new CountDownLatch(1)  
  }
  class OneWayTestActor extends Actor {
    dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(uuid)
    def receive = {
      case "OneWay" => OneWayTestActor.oneWay.countDown
    }
  }  
}
class ExecutorBasedEventDrivenDispatcherActorSpec extends JUnitSuite {
  import ExecutorBasedEventDrivenDispatcherActorSpec._
  
  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldSendOneWay = {
    val actor = newActor[OneWayTestActor]
    actor.start
    val result = actor ! "OneWay"
    assert(OneWayTestActor.oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test def shouldSendReplySync = {
    val actor = newActor[TestActor]
    actor.start
    val result: String = (actor !! ("Hello", 10000)).get
    assert("World" === result)
    actor.stop
  }

  @Test def shouldSendReplyAsync = {
    val actor = newActor[TestActor]
    actor.start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test def shouldSendReceiveException = {
    val actor = newActor[TestActor]
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
