package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import Actor._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.junit.Test
import se.scalablesolutions.akka.config.ScalaConfig._
import org.multiverse.api.latches.StandardLatch

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

  /**
   * ticket #307
   */
  @Test def actorRestartShouldPickUpScheduleAgain = {

    try {
      object Ping
      object Crash

      val restartLatch = new StandardLatch
      val pingLatch = new CountDownLatch(4)

      val actor = actorOf(new Actor {
        self.lifeCycle = Some(LifeCycle(Permanent))

        def receive = {
          case Ping => pingLatch.countDown
          case Crash => throw new Exception("CRASH")
        }

        override def postRestart(reason: Throwable) =  restartLatch.open
      })

      Supervisor(
        SupervisorConfig(
          RestartStrategy(AllForOne, 3, 1000,
            List(classOf[Exception])),
            Supervise(actor, LifeCycle(Permanent)) :: Nil)).start

      Scheduler.schedule(actor, Ping, 500, 500, TimeUnit.MILLISECONDS)
      // appx 2 pings before crash
      Scheduler.scheduleOnce(actor, Crash, 1000, TimeUnit.MILLISECONDS)

      assert(restartLatch.tryAwait(4, TimeUnit.SECONDS))
      // should be enough time for the ping countdown to recover and reach 4 pings
      assert(pingLatch.await(4, TimeUnit.SECONDS))

    } finally {
      Scheduler.restart
    }
  }
}
