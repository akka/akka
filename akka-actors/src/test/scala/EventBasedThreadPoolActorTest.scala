package se.scalablesolutions.akka.actor

import java.util.concurrent.TimeUnit

import org.scalatest.junit.JUnitSuite
import org.junit.Test

class EventBasedThreadPoolActorTest extends JUnitSuite {
  private val unit = TimeUnit.MILLISECONDS

  class TestActor extends Actor {
    def receive = {
      case "Hello" =>
        reply("World")
      case "Failure" =>
        throw new RuntimeException("expected")
    }
  }

  @Test def shouldSendOneWay = {
    implicit val timeout = 5000L
    var oneWay = "nada"
    val actor = new Actor {
      def receive = {
        case "OneWay" => oneWay = "received"
      }
    }
    actor.start
    val result = actor ! "OneWay"
    Thread.sleep(100)
    assert("received" === oneWay)
    actor.exit
  }

  @Test def shouldSendReplySync = {
    implicit val timeout = 5000L
    val actor = new TestActor
    actor.start
    val result: String = actor !? "Hello"
    assert("World" === result)
    actor.exit
  }

  @Test def shouldSendReplyAsync = {
    implicit val timeout = 5000L
    val actor = new TestActor
    actor.start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.exit
  }

  @Test def shouldSendReceiveException = {
    implicit val timeout = 5000L
    val actor = new TestActor
    actor.start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("expected" === e.getMessage())
    }
    actor.exit
  }
}
