package se.scalablesolutions.akka.actor

import java.util.concurrent.TimeUnit

import junit.framework.Assert._

class EventBasedThreadPoolActorSpec extends junit.framework.TestCase {
  private val unit = TimeUnit.MILLISECONDS

  class TestActor extends Actor {
    def receive: PartialFunction[Any, Unit] = {
      case "Hello" =>
        reply("World")
      case "Failure" =>
        throw new RuntimeException("expected")
    }
  }

  def testSendOneWay = {
    implicit val timeout = 5000L
    var oneWay = "nada"
    val actor = new Actor {
      def receive: PartialFunction[Any, Unit] = {
        case "OneWay" => oneWay = "received"
      }
    }
    actor.start
    val result = actor ! "OneWay"
    Thread.sleep(100)
    assertEquals("received", oneWay)
    actor.stop
  }

  def testSendReplySync = {
    implicit val timeout = 5000L
    val actor = new TestActor
    actor.start
    val result: String = actor !? "Hello"
    assertEquals("World", result)
    actor.stop
  }

  def testSendReplyAsync = {
    implicit val timeout = 5000L
    val actor = new TestActor
    actor.start
    val result = actor !! "Hello"
    assertEquals("World", result.get.asInstanceOf[String])
    actor.stop
  }

  def testSendReceiveException = {
    implicit val timeout = 5000L
    val actor = new TestActor
    actor.start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assertEquals("expected", e.getMessage())
    }
    actor.stop
  }
}
