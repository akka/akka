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
import akka.util.Duration
import java.util.concurrent.TimeoutException

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
    bal-disp {
      type = BalancingDispatcher
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

    "be possible to define programmatically" in {

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

    "resize when busy" ignore {

      val busy = new TestLatch(1)

      val resizer = DefaultResizer(
        lowerBound = 1,
        upperBound = 3,
        pressureThreshold = 0,
        messagesPerResize = 1)

      val router = system.actorOf(Props[BusyActor].withRouter(RoundRobinRouter(resizer = Some(resizer))).withDispatcher("bal-disp"))

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
        lowerBound = 3,
        upperBound = 5,
        rampupRate = 0.1,
        backoffRate = 0.0,
        pressureThreshold = 1,
        messagesPerResize = 1,
        backoffThreshold = 0.0)

      val router = system.actorOf(Props(new Actor {
        def receive = {
          case d: Duration ⇒ d.dilated.sleep; sender ! "done"
          case "echo"      ⇒ sender ! "reply"
        }
      }).withRouter(RoundRobinRouter(resizer = Some(resizer))))

      // first message should create the minimum number of routees
      router ! "echo"
      expectMsg("reply")

      def routees(r: ActorRef): Int = {
        r ! CurrentRoutees
        expectMsgType[RouterRoutees].routees.size
      }

      routees(router) must be(3)

      def loop(loops: Int, d: Duration) = {
        for (m ← 0 until loops) router ! d
        for (m ← 0 until loops) expectMsg(d * 3, "done")
      }

      // 2 more should go thru without triggering more
      loop(2, 200 millis)

      routees(router) must be(3)

      // a whole bunch should max it out
      loop(10, 500 millis)
      awaitCond(routees(router) > 3)

      loop(10, 500 millis)
      awaitCond(routees(router) == 5)
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
      for (m ← 0 to 5) {
        router ! 1
        (500 millis).dilated.sleep
      }

      awaitCond(
        try {
          Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size < (z)
        } catch {
          case _: TimeoutException ⇒ false
        }, 1 minute)
    }

  }

}
