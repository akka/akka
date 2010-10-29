package akka.actor.dispatch

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import akka.dispatch.{HawtDispatcher, Dispatchers}
import akka.actor.Actor
import Actor._

object HawtDispatcherActorSpec {
  class TestActor extends Actor {
    self.dispatcher = new HawtDispatcher()
    def receive = {
      case "Hello" =>
        self.reply("World")
      case "Failure" =>
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  object OneWayTestActor {
    val oneWay = new CountDownLatch(1)
  }
  class OneWayTestActor extends Actor {
    self.dispatcher = new HawtDispatcher()
    def receive = {
      case "OneWay" => OneWayTestActor.oneWay.countDown
    }
  }
}

class HawtDispatcherActorSpec extends JUnitSuite {
  import HawtDispatcherActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldSendOneWay = {
    val actor = actorOf[OneWayTestActor].start
    val result = actor ! "OneWay"
    assert(OneWayTestActor.oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test def shouldSendReplySync = {
    val actor = actorOf[TestActor].start
    val result = (actor !! ("Hello", 10000)).as[String]
    assert("World" === result.get)
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
