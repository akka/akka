package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import akka.testkit.{ filterEvents, EventFilter, AkkaSpec }
import akka.dispatch.{ PinnedDispatcher, Dispatchers, Dispatcher }
import akka.actor.{ Props, Actor }

object DispatcherActorSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ reply("World")
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

class DispatcherActorSpec extends AkkaSpec {
  import DispatcherActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  "A Dispatcher and an Actor" must {

    "support tell" in {
      val actor = createActor(Props[OneWayTestActor].withDispatcher(app.dispatcherFactory.newDispatcher("test").build))
      val result = actor ! "OneWay"
      assert(OneWayTestActor.oneWay.await(1, TimeUnit.SECONDS))
      actor.stop()
    }

    "support sendReplySync" in {
      val actor = createActor(Props[TestActor].withDispatcher(app.dispatcherFactory.newDispatcher("test").build))
      val result = (actor.?("Hello", 10000)).as[String]
      assert("World" === result.get)
      actor.stop()
      sys.error("what sense does this test make?")
    }

    "support ask/reply" in {
      val actor = createActor(Props[TestActor].withDispatcher(app.dispatcherFactory.newDispatcher("test").build))
      val result = (actor ? "Hello").as[String]
      assert("World" === result.get)
      actor.stop()
    }

    "support ask/exception" in {
      filterEvents(EventFilter[RuntimeException]("Expected")) {
        val actor = createActor(Props[TestActor].withDispatcher(app.dispatcherFactory.newDispatcher("test").build))
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

    "respect the throughput setting" in {
      val throughputDispatcher = app.dispatcherFactory.
        newDispatcher("THROUGHPUT", 101, 0, app.dispatcherFactory.MailboxType).
        setCorePoolSize(1).
        build

      val works = new AtomicBoolean(true)
      val latch = new CountDownLatch(100)
      val start = new CountDownLatch(1)
      val fastOne = createActor(
        Props(context ⇒ { case "sabotage" ⇒ works.set(false) }).withDispatcher(throughputDispatcher))

      val slowOne = createActor(
        Props(context ⇒ {
          case "hogexecutor" ⇒ start.await
          case "ping"        ⇒ if (works.get) latch.countDown()
        }).withDispatcher(throughputDispatcher))

      slowOne ! "hogexecutor"
      (1 to 100) foreach { _ ⇒ slowOne ! "ping" }
      fastOne ! "sabotage"
      start.countDown()
      val result = latch.await(5, TimeUnit.SECONDS)
      fastOne.stop()
      slowOne.stop()
      assert(result === true)
    }

    "respect throughput deadline" in {
      val deadlineMs = 100
      val throughputDispatcher = app.dispatcherFactory.
        newDispatcher("THROUGHPUT", 2, deadlineMs, app.dispatcherFactory.MailboxType).
        setCorePoolSize(1).
        build
      val works = new AtomicBoolean(true)
      val latch = new CountDownLatch(1)
      val start = new CountDownLatch(1)
      val ready = new CountDownLatch(1)

      val fastOne = createActor(
        Props(context ⇒ {
          case "ping" ⇒ if (works.get) latch.countDown(); context.self.stop()
        }).withDispatcher(throughputDispatcher))

      val slowOne = createActor(
        Props(context ⇒ {
          case "hogexecutor" ⇒ ready.countDown(); start.await
          case "ping"        ⇒ works.set(false); context.self.stop()
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
}
