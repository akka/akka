/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.throttle

import java.util.concurrent.TimeUnit._

import akka.actor.ActorSystem
import akka.contrib.throttle.Throttler._
import akka.testkit.{ TestActorRef, TestKit }
import org.scalatest.WordSpecLike

class TimerBasedThrottleTest extends TestKit(ActorSystem("TimerBasedThrottler")) with WordSpecLike {
  "A throttler" must {
    "normalize all rates to the highest precision (nanoseconds)" in {
      val throttler = TestActorRef(new TimerBasedThrottler(1.msgsPer(1, SECONDS)))
      val throttler2 = TestActorRef(new TimerBasedThrottler(5.msgsPer(1, SECONDS)))
      val throttler3 = TestActorRef(new TimerBasedThrottler(10.msgsPer(10, MILLISECONDS)))
      val throttler4 = TestActorRef(new TimerBasedThrottler(1.msgsPer(1, MINUTES)))

      assert(throttler.underlyingActor.rate.duration.toNanos == 1e9)
      assert(throttler.underlyingActor.rate.numberOfCalls == 1)

      assert(throttler2.underlyingActor.rate.duration.toNanos == 1e9 / 5)
      assert(throttler2.underlyingActor.rate.numberOfCalls == 1)

      assert(throttler3.underlyingActor.rate.duration.toNanos == 1e6 * 10 / 10) // Convert ms to nanos
      assert(throttler3.underlyingActor.rate.numberOfCalls == 1)

      assert(throttler4.underlyingActor.rate.duration.toNanos == 1e9 * 60)
      assert(throttler4.underlyingActor.rate.numberOfCalls == 1)
    }

    "handle zero number of calls gracefully" in {
      val throttler = TestActorRef(new TimerBasedThrottler(0.msgsPer(1, SECONDS)))

      assert(throttler.underlyingActor.rate.duration.toSeconds == 1)
      assert(throttler.underlyingActor.rate.numberOfCalls == 0)
    }
  }
}
