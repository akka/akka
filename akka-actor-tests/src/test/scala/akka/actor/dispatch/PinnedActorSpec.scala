package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import org.scalatest.junit.JUnitSuite
import org.junit.{ Test, Before, After }

import akka.actor.Actor._

import akka.event.EventHandler
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
import akka.actor.{ Props, Actor }

object PinnedActorSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ self.reply("World")
      case "Failure" ⇒ throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class PinnedActorSpec extends JUnitSuite {
  import PinnedActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  @Before
  def beforeEach {
    EventHandler.notify(Mute(EventFilter[RuntimeException]("Failure")))
  }

  @After
  def afterEach {
    EventHandler.notify(UnMuteAll)
  }

  @Test
  def shouldTell {
    var oneWay = new CountDownLatch(1)
    val actor = actorOf(Props(self ⇒ { case "OneWay" ⇒ oneWay.countDown() }).withDispatcher(new PinnedDispatcher()))
    val result = actor ! "OneWay"
    assert(oneWay.await(1, TimeUnit.SECONDS))
    actor.stop()
  }

  @Test
  def shouldSendReplySync = {
    val actor = actorOf(Props[TestActor].withDispatcher(new PinnedDispatcher()))
    val result = (actor.?("Hello", 10000)).as[String]
    assert("World" === result.get)
    actor.stop()
  }

  @Test
  def shouldSendReplyAsync = {
    val actor = actorOf(Props[TestActor].withDispatcher(new PinnedDispatcher()))
    val result = (actor ? "Hello").as[String]
    assert("World" === result.get)
    actor.stop()
  }

  @Test
  def shouldSendReceiveException = {
    val actor = actorOf(Props[TestActor].withDispatcher(new PinnedDispatcher()))
    try {
      (actor ? "Failure").get
      fail("Should have thrown an exception")
    } catch {
      case e ⇒
        assert("Expected exception; to test fault-tolerance" === e.getMessage())
    }
    actor.stop()
  }
}
