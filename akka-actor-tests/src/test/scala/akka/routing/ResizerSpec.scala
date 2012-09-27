/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import language.postfixOps
import akka.actor.Actor
import akka.testkit._
import akka.testkit.TestEvent._
import akka.actor.Props
import scala.concurrent.Await
import scala.concurrent.util.duration._
import akka.actor.ActorRef
import java.util.concurrent.atomic.AtomicInteger
import akka.pattern.ask
import scala.concurrent.util.Duration
import java.util.concurrent.TimeoutException
import scala.concurrent.util.FiniteDuration
import scala.util.Try

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

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ResizerSpec extends AkkaSpec(ResizerSpec.config) with DefaultTimeout with ImplicitSender {

  import akka.routing.ResizerSpec._

  override def atStartup: Unit = {
    // when shutting down some Resize messages might hang around
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*Resize")))
  }

  def routeeSize(router: ActorRef): Int =
    Await.result(router ? CurrentRoutees, remaining).asInstanceOf[RouterRoutees].routees.size

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

      Await.ready(latch, remaining)

      // messagesPerResize is 10 so there is no risk of additional resize
      routeeSize(router) must be(2)
    }

    "be possible to define in configuration" in {
      val latch = new TestLatch(3)

      val router = system.actorOf(Props[TestActor].withRouter(FromConfig()), "router1")

      router ! latch
      router ! latch
      router ! latch

      Await.ready(latch, remaining)

      routeeSize(router) must be(2)
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
          case d: FiniteDuration ⇒ Thread.sleep(d.dilated.toMillis); sender ! "done"
          case "echo"            ⇒ sender ! "reply"
        }
      }).withRouter(RoundRobinRouter(resizer = Some(resizer))))

      // first message should create the minimum number of routees
      router ! "echo"
      expectMsg("reply")

      routeeSize(router) must be(resizer.lowerBound)

      def loop(loops: Int, d: FiniteDuration) = {
        for (m ← 0 until loops) {
          router ! d
          // sending in too quickly will result in skipped resize due to many resizeInProgress conflicts
          Thread.sleep(20.millis.dilated.toMillis)
        }
        within((((d * loops).asInstanceOf[FiniteDuration] / resizer.lowerBound) + 2.seconds.dilated).asInstanceOf[FiniteDuration]) {
          for (m ← 0 until loops) expectMsg("done")
        }
      }

      // 2 more should go thru without triggering more
      loop(2, 200 millis)
      routeeSize(router) must be(resizer.lowerBound)

      // a whole bunch should max it out
      loop(20, 500 millis)
      routeeSize(router) must be(resizer.upperBound)
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
          case n: Int ⇒ Thread.sleep((n millis).dilated.toMillis)
        }
      }).withRouter(RoundRobinRouter(resizer = Some(resizer))))

      // put some pressure on the router
      for (m ← 0 to 5) {
        router ! 100
        Thread.sleep((20 millis).dilated.toMillis)
      }

      val z = routeeSize(router)
      z must be >= (2)

      Thread.sleep((300 millis).dilated.toMillis)

      // let it cool down
      for (m ← 0 to 5) {
        router ! 1
        Thread.sleep((500 millis).dilated.toMillis)
      }

      awaitCond(Try(routeeSize(router) < (z)).getOrElse(false))
    }

  }

}
