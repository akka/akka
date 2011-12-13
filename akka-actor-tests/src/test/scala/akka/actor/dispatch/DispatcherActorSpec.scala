package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import akka.testkit.{ filterEvents, EventFilter, AkkaSpec }
import akka.actor.{ Props, Actor }
import akka.util.Duration
import akka.util.duration._
import akka.testkit.DefaultTimeout
import akka.dispatch.{ Await, PinnedDispatcher, Dispatchers, Dispatcher }

object DispatcherActorSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ sender ! "World"
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatcherActorSpec extends AkkaSpec with DefaultTimeout {
  import DispatcherActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  "A Dispatcher and an Actor" must {

    "support tell" in {
      val actor = system.actorOf(Props[OneWayTestActor].withDispatcher(system.dispatcherFactory.newDispatcher("test").build))
      val result = actor ! "OneWay"
      assert(OneWayTestActor.oneWay.await(1, TimeUnit.SECONDS))
      actor.stop()
    }

    "support ask/reply" in {
      val actor = system.actorOf(Props[TestActor].withDispatcher(system.dispatcherFactory.newDispatcher("test").build))
      assert("World" === Await.result(actor ? "Hello", timeout.duration))
      actor.stop()
    }

    "respect the throughput setting" in {
      val throughputDispatcher = system.dispatcherFactory.
        newDispatcher("THROUGHPUT", 101, Duration.Zero, system.dispatcherFactory.MailboxType).
        setCorePoolSize(1).
        build

      val works = new AtomicBoolean(true)
      val latch = new CountDownLatch(100)
      val start = new CountDownLatch(1)
      val fastOne = system.actorOf(
        Props(context ⇒ { case "sabotage" ⇒ works.set(false) }).withDispatcher(throughputDispatcher))

      val slowOne = system.actorOf(
        Props(context ⇒ {
          case "hogexecutor" ⇒ context.sender ! "OK"; start.await
          case "ping"        ⇒ if (works.get) latch.countDown()
        }).withDispatcher(throughputDispatcher))

      assert(Await.result(slowOne ? "hogexecutor", timeout.duration) === "OK")
      (1 to 100) foreach { _ ⇒ slowOne ! "ping" }
      fastOne ! "sabotage"
      start.countDown()
      latch.await(10, TimeUnit.SECONDS)
      fastOne.stop()
      slowOne.stop()
      assert(latch.getCount() === 0)
    }

    "respect throughput deadline" in {
      val deadline = 100 millis
      val throughputDispatcher = system.dispatcherFactory.
        newDispatcher("THROUGHPUT", 2, deadline, system.dispatcherFactory.MailboxType).
        setCorePoolSize(1).
        build
      val works = new AtomicBoolean(true)
      val latch = new CountDownLatch(1)
      val start = new CountDownLatch(1)
      val ready = new CountDownLatch(1)

      val fastOne = system.actorOf(
        Props(context ⇒ {
          case "ping" ⇒ if (works.get) latch.countDown(); context.self.stop()
        }).withDispatcher(throughputDispatcher))

      val slowOne = system.actorOf(
        Props(context ⇒ {
          case "hogexecutor" ⇒ ready.countDown(); start.await
          case "ping"        ⇒ works.set(false); context.self.stop()
        }).withDispatcher(throughputDispatcher))

      slowOne ! "hogexecutor"
      slowOne ! "ping"
      fastOne ! "ping"
      assert(ready.await(2, TimeUnit.SECONDS) === true)
      Thread.sleep(deadline.toMillis + 10) // wait just a bit more than the deadline
      start.countDown()
      assert(latch.await(2, TimeUnit.SECONDS) === true)
    }
  }
}
