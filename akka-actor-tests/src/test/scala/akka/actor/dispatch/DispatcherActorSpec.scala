package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.actor.Actor._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import akka.testkit.{ filterEvents, EventFilter }
import akka.dispatch.{ PinnedDispatcher, Dispatchers, Dispatcher }
import akka.actor.{ Props, Actor }

object DispatcherActorSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ self.reply("World")
      case "Failure" ⇒ throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  object OneWayTestActor {
    val oneWay = new CountDownLatch(1)
  }
  class OneWayTestActor extends Actor {
    def receive = {
      case "OneWay" ⇒ OneWayTestActor.oneWay.countDown()
    }
  }
}
class DispatcherActorSpec extends JUnitSuite {
  import DispatcherActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  @Test
  def shouldTell = {
    val actor = actorOf(Props[OneWayTestActor].withDispatcher(new PinnedDispatcher()))
    val result = actor ! "OneWay"
    assert(OneWayTestActor.oneWay.await(1, TimeUnit.SECONDS))
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
    filterEvents(EventFilter[RuntimeException]("Expected")) {
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

  @Test
  def shouldRespectThroughput {
    val throughputDispatcher = Dispatchers.
      newDispatcher("THROUGHPUT", 101, 0, Dispatchers.MAILBOX_TYPE).
      setCorePoolSize(1).
      build

    val works = new AtomicBoolean(true)
    val latch = new CountDownLatch(100)
    val start = new CountDownLatch(1)
    val fastOne = actorOf(
      Props(self ⇒ { case "sabotage" ⇒ works.set(false) }).withDispatcher(throughputDispatcher))

    val slowOne = actorOf(
      Props(self ⇒ {
        case "hogexecutor" ⇒ start.await
        case "ping"        ⇒ if (works.get) latch.countDown()
      }).withDispatcher(throughputDispatcher))

    slowOne ! "hogexecutor"
    (1 to 100) foreach { _ ⇒ slowOne ! "ping" }
    fastOne ! "sabotage"
    start.countDown()
    val result = latch.await(3, TimeUnit.SECONDS)
    fastOne.stop()
    slowOne.stop()
    assert(result === true)
  }

  @Test
  def shouldRespectThroughputDeadline {
    val deadlineMs = 100
    val throughputDispatcher = Dispatchers.
      newDispatcher("THROUGHPUT", 2, deadlineMs, Dispatchers.MAILBOX_TYPE).
      setCorePoolSize(1).
      build
    val works = new AtomicBoolean(true)
    val latch = new CountDownLatch(1)
    val start = new CountDownLatch(1)
    val ready = new CountDownLatch(1)

    val fastOne = actorOf(
      Props(self ⇒ {
        case "ping" ⇒ if (works.get) latch.countDown(); self.stop()
      }).withDispatcher(throughputDispatcher))

    val slowOne = actorOf(
      Props(self ⇒ {
        case "hogexecutor" ⇒ ready.countDown(); start.await
        case "ping"        ⇒ works.set(false); self.stop()
      }).withDispatcher(throughputDispatcher))

    slowOne ! "hogexecutor"
    slowOne ! "ping"
    fastOne ! "ping"
    assert(ready.await(2, TimeUnit.SECONDS) === true)
    Thread.sleep(deadlineMs + 10) // wait just a bit more than the deadline
    start.countDown()
    assert(latch.await(2, TimeUnit.SECONDS) === true)
  }
}
