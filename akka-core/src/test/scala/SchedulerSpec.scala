package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import Actor._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.junit.{After, Test}

class SchedulerSpec extends JUnitSuite {
  
  @Test def schedulerShouldScheduleMoreThanOnce = {

    case object Tick
    val countDownLatch = new CountDownLatch(3)
    val tickActor = actor {
      case Tick => countDownLatch.countDown
    }
    // run every 50 millisec
    Scheduler.schedule(tickActor, Tick, 0, 50, TimeUnit.MILLISECONDS)

    // after max 1 second it should be executed at least the 3 times already
    assert(countDownLatch.await(1, TimeUnit.SECONDS))
  }

  @Test def schedulerShouldScheduleOnce = {
    case object Tick
    val countDownLatch = new CountDownLatch(2)
    val tickActor = actor {
      case Tick => countDownLatch.countDown
    }
    // run every 50 millisec
    Scheduler.scheduleOnce(tickActor, Tick, 50, TimeUnit.MILLISECONDS)

    // after 1 second the wait should fail
    assert(countDownLatch.await(1, TimeUnit.SECONDS) == false)
    // should still be 1 left
    assert(countDownLatch.getCount == 1)
  }
}
