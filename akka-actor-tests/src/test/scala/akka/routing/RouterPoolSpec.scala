/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.actor.Actor
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestLatch
import akka.actor.Props
import akka.dispatch.Await
import akka.util.duration._
import akka.actor.ActorRef

object RouterPoolSpec {

  val config = """
    akka.actor.deployment {
      /router1 {
        router = round-robin
        pool {
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

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RouterPoolSpec extends AkkaSpec(RouterPoolSpec.config) with DefaultTimeout with ImplicitSender {

  import akka.routing.RouterPoolSpec._

  "DefaultRouterPool" must {

    "use settings to evaluate capacity" in {
      val pool = DefaultRouterPool(
        lowerBound = 2,
        upperBound = 3)

      val c1 = pool.capacity(IndexedSeq.empty[ActorRef])
      c1 must be(2)

      val current = IndexedSeq(system.actorOf(Props[TestActor]), system.actorOf(Props[TestActor]))
      val c2 = pool.capacity(current)
      c2 must be(0)
    }

    "be possible to define programatically" in {
      val latch = new TestLatch(3)

      val pool = DefaultRouterPool(
        lowerBound = 2,
        upperBound = 3)
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(pool = Some(pool))))

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

  }

}
