package akka.actor

import org.scalatest.junit.JUnitSuite
import Actor._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import akka.config.Supervision._
import org.multiverse.api.latches.StandardLatch
import org.junit.Test

class SchedulerSpec extends JUnitSuite {

  def withCleanEndState(action: => Unit) {
    action
    Scheduler.restart
    ActorRegistry.shutdownAll
  }


  @Test def schedulerShouldScheduleMoreThanOnce = withCleanEndState {

    case object Tick
    val countDownLatch = new CountDownLatch(3)
    val tickActor = actorOf(new Actor {
      def receive = { case Tick => countDownLatch.countDown }
    }).start
    // run every 50 millisec
    Scheduler.schedule(tickActor, Tick, 0, 50, TimeUnit.MILLISECONDS)

    // after max 1 second it should be executed at least the 3 times already
    assert(countDownLatch.await(1, TimeUnit.SECONDS))

    val countDownLatch2 = new CountDownLatch(3)

    Scheduler.schedule( () => countDownLatch2.countDown, 0, 50, TimeUnit.MILLISECONDS)

    // after max 1 second it should be executed at least the 3 times already
    assert(countDownLatch2.await(1, TimeUnit.SECONDS))
  }

  @Test def schedulerShouldScheduleOnce = withCleanEndState {
    case object Tick
    val countDownLatch = new CountDownLatch(3)
    val tickActor = actorOf(new Actor {
      def receive = { case Tick => countDownLatch.countDown }
    }).start
    // run every 50 millisec
    Scheduler.scheduleOnce(tickActor, Tick, 50, TimeUnit.MILLISECONDS)
    Scheduler.scheduleOnce( () => countDownLatch.countDown, 50, TimeUnit.MILLISECONDS)

    // after 1 second the wait should fail
    assert(countDownLatch.await(1, TimeUnit.SECONDS) == false)
    // should still be 1 left
    assert(countDownLatch.getCount == 1)
  }

  /**
   * ticket #372
   */
  @Test def schedulerShouldntCreateActors = withCleanEndState {
    object Ping
    val ticks = new CountDownLatch(1000)
    val actor = actorOf(new Actor {
      def receive = { case Ping => ticks.countDown }
    }).start
    val numActors = ActorRegistry.actors.length
    (1 to 1000).foreach( _ => Scheduler.scheduleOnce(actor,Ping,1,TimeUnit.MILLISECONDS) )
    assert(ticks.await(10,TimeUnit.SECONDS))
    assert(ActorRegistry.actors.length === numActors)
  }

  /**
   * ticket #372
   */
  @Test def schedulerShouldBeCancellable = withCleanEndState {
    object Ping
    val ticks = new CountDownLatch(1)

    val actor = actorOf(new Actor {
      def receive = { case Ping => ticks.countDown }
    }).start

    (1 to 10).foreach { i =>
      val future = Scheduler.scheduleOnce(actor,Ping,1,TimeUnit.SECONDS)
      future.cancel(true)
    }
    assert(ticks.await(3,TimeUnit.SECONDS) == false) //No counting down should've been made
  }

  /**
   * ticket #307
   */
  @Test def actorRestartShouldPickUpScheduleAgain = withCleanEndState {

    object Ping
    object Crash

    val restartLatch = new StandardLatch
    val pingLatch = new CountDownLatch(6)

    val actor = actorOf(new Actor {
      self.lifeCycle = Permanent

      def receive = {
        case Ping => pingLatch.countDown
        case Crash => throw new Exception("CRASH")
      }

      override def postRestart(reason: Throwable) = restartLatch.open
    })

    Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 1000),
        Supervise(
          actor,
          Permanent)
                :: Nil)).start

    Scheduler.schedule(actor, Ping, 500, 500, TimeUnit.MILLISECONDS)
    // appx 2 pings before crash
    Scheduler.scheduleOnce(actor, Crash, 1000, TimeUnit.MILLISECONDS)

    assert(restartLatch.tryAwait(2, TimeUnit.SECONDS))
    // should be enough time for the ping countdown to recover and reach 6 pings
    assert(pingLatch.await(4, TimeUnit.SECONDS))
  }
}
