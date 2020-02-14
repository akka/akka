/*
 * Copyright (C) 2020-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt

class ExplicitlyTriggeredSchedulerSpec extends AkkaSpec {

  "ExplicitlyTriggeredScheduler" must {

    "execute a scheduled task" in new TestScope {
      scheduler.schedule(0.seconds, 5.seconds, runnable)(system.dispatcher)
      scheduler.timePasses(12.seconds)

      counter.get() shouldBe 3
    }

    "execute a scheduled task with initial delay" in new TestScope {
      scheduler.schedule(5.seconds, 5.seconds, runnable)(system.dispatcher)
      scheduler.timePasses(12.seconds)

      counter.get() shouldBe 2
    }

    "cancel scheduled task" in new TestScope {
      val task = scheduler.schedule(5.seconds, 5.seconds, runnable)(system.dispatcher)

      scheduler.timePasses(7.seconds) // 7s
      counter.get() shouldBe 1

      val cancellationResult = task.cancel()
      cancellationResult shouldBe true

      scheduler.timePasses(4.seconds) // 11s
      counter.get() shouldBe 1
    }

    "allow to move in time many times" in new TestScope {
      scheduler.schedule(5.seconds, 5.seconds, runnable)(system.dispatcher)

      scheduler.timePasses(7.seconds) // 7s
      counter.get() shouldBe 1

      scheduler.timePasses(1.seconds) // 8s
      counter.get() shouldBe 1

      scheduler.timePasses(10.seconds) // 18s
      counter.get() shouldBe 3
    }

    "execute a scheduled task only once" in new TestScope {
      scheduler.scheduleOnce(5.seconds, runnable)(system.dispatcher)
      scheduler.timePasses(100.seconds)

      counter.get() shouldBe 1
    }
  }

  trait TestScope {
    val counter = new AtomicInteger()
    def runnable: Runnable = () => counter.incrementAndGet()
    val scheduler = new ExplicitlyTriggeredScheduler(config = null, log = log, tf = null)
  }

}
