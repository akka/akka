/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor.Actor
import akka.testkit._
import akka.actor.Props
import akka.dispatch.Await
import akka.util.duration._
import akka.actor.ActorRef
import java.util.concurrent.atomic.AtomicInteger
import akka.pattern.ask

object ResizerSpec {

  val config = """
    akka.actor.deployment {
      /router1 {
        router = round-robin
        resizer {
          lower-bound = 2
          upper-bound = 3
        }
      }
    }
    """

  class TestActor extends Actor {
    def receive = {
      case latch: TestLatch ⇒ latch.countDown()
    }
  }

  class BusyActor extends Actor {
    def receive = {
      case (latch: TestLatch, busy: TestLatch) ⇒
        latch.countDown()
        Await.ready(busy, 5 seconds)
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ResizerSpec extends AkkaSpec(ResizerSpec.config) with DefaultTimeout with ImplicitSender {

  import akka.routing.ResizerSpec._

  "DefaultResizer" must {

    "use settings to evaluate capacity" in {
      val resizer = DefaultResizer(
        lowerBound = 2,
        upperBound = 3)

      val c1 = resizer.capacity(IndexedSeq.empty[ActorRef])
      c1 must be(2)

      val current = IndexedSeq(system.actorOf(Props[TestActor]), system.actorOf(Props[TestActor]))
      val c2 = resizer.capacity(current)
      c2 must be(0)
    }

    "use settings to evaluate rampUp" in {
      val resizer = DefaultResizer(
        lowerBound = 2,
        upperBound = 10,
        rampupRate = 0.2)

      resizer.rampup(pressure = 9, capacity = 10) must be(0)
      resizer.rampup(pressure = 5, capacity = 5) must be(1)
      resizer.rampup(pressure = 6, capacity = 6) must be(2)
    }

    "use settings to evaluate backoff" in {
      val resizer = DefaultResizer(
        lowerBound = 2,
        upperBound = 10,
        backoffThreshold = 0.3,
        backoffRate = 0.1)

      resizer.backoff(pressure = 10, capacity = 10) must be(0)
      resizer.backoff(pressure = 4, capacity = 10) must be(0)
      resizer.backoff(pressure = 3, capacity = 10) must be(0)
      resizer.backoff(pressure = 2, capacity = 10) must be(-1)
      resizer.backoff(pressure = 0, capacity = 10) must be(-1)
      resizer.backoff(pressure = 1, capacity = 9) must be(-1)
      resizer.backoff(pressure = 0, capacity = 9) must be(-1)
    }

    "be possible to define programatically" in {

      val latch = new TestLatch(3)

      val resizer = DefaultResizer(
        lowerBound = 2,
        upperBound = 3)
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(resizer = Some(resizer))))

      router ! latch
      router ! latch
      router ! latch

      Await.ready(latch, 5 seconds)

      val current = Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees]
      current.routees.size must be(2)
    }

    "be possible to define in configuration" in {
      val latch = new TestLatch(3)

      val router = system.actorOf(Props[TestActor].withRouter(FromConfig()), "router1")

      router ! latch
      router ! latch
      router ! latch

      Await.ready(latch, 5 seconds)

      val current = Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees]
      current.routees.size must be(2)
    }

    /*
     * TODO RK This test seems invalid to me, because it relies on that no resize() event is lost;
     * this currently fails because I made resize() asynchronous (by sending a message to the
     * Router), but it could also fail for concurrent send operations, i.e. when one of thread
     * fails the resizeInProgress.compareAndSet(false, true) check.
     * 
     * Either the test must be fixed/removed or resize() must be changed to be blocking.
     */
    "resize when busy" ignore {

      val busy = new TestLatch(1)

      val resizer = DefaultResizer(
        lowerBound = 1,
        upperBound = 3,
        pressureThreshold = 0,
        messagesPerResize = 1)

      val router = system.actorOf(Props[BusyActor].withRouter(RoundRobinRouter(resizer = Some(resizer))))

      val latch1 = new TestLatch(1)
      router ! (latch1, busy)
      Await.ready(latch1, 2 seconds)

      val latch2 = new TestLatch(1)
      router ! (latch2, busy)
      Await.ready(latch2, 2 seconds)

      val latch3 = new TestLatch(1)
      router ! (latch3, busy)
      Await.ready(latch3, 2 seconds)

      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be(3)

      busy.countDown()
    }

    "grow as needed under pressure" in {
      // make sure the pool starts at the expected lower limit and grows to the upper as needed
      // as influenced by the backlog of blocking pooled actors

      val resizer = DefaultResizer(
        lowerBound = 2,
        upperBound = 4,
        rampupRate = 0.1,
        pressureThreshold = 1,
        messagesPerResize = 1,
        backoffThreshold = 0.0)

      val router = system.actorOf(Props(new Actor {
        def receive = {
          case (n: Int, latch: TestLatch, count: AtomicInteger) ⇒
            (n millis).dilated.sleep
            count.incrementAndGet
            latch.countDown()
        }
      }).withRouter(RoundRobinRouter(resizer = Some(resizer))))

      // first message should create the minimum number of routees
      router ! 1

      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be(2)

      def loop(loops: Int, t: Int, latch: TestLatch, count: AtomicInteger) = {
        (100 millis).dilated.sleep
        for (m ← 0 until loops) {
          router.!((t, latch, count))
          (100 millis).dilated.sleep
        }
      }

      // 2 more should go thru without triggering more
      val count1 = new AtomicInteger
      val latch1 = TestLatch(2)
      loop(2, 200, latch1, count1)
      Await.ready(latch1, TestLatch.DefaultTimeout)
      count1.get must be(2)

      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be(2)

      // a whole bunch should max it out
      val count2 = new AtomicInteger
      val latch2 = TestLatch(10)
      loop(10, 500, latch2, count2)
      Await.ready(latch2, TestLatch.DefaultTimeout)
      count2.get must be(10)

      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be(4)

    }

    "backoff" in {

      val resizer = DefaultResizer(
        lowerBound = 1,
        upperBound = 5,
        rampupRate = 1.0,
        backoffRate = 1.0,
        backoffThreshold = 0.20,
        pressureThreshold = 1,
        messagesPerResize = 1)

      val router = system.actorOf(Props(new Actor {
        def receive = {
          case n: Int ⇒
            (n millis).dilated.sleep
        }
      }).withRouter(RoundRobinRouter(resizer = Some(resizer))))

      // put some pressure on the router
      for (m ← 0 to 5) {
        router ! 100
        (5 millis).dilated.sleep
      }

      val z = Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size
      z must be >= (2)

      (300 millis).dilated.sleep

      // let it cool down
      for (m ← 0 to 3) {
        router ! 1
        (500 millis).dilated.sleep
      }

      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be < (z)

    }

  }

}
