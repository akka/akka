package akka.actor

import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterEach
import akka.event.EventHandler
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import Actor._
import akka.config.Supervision._
import org.multiverse.api.latches.StandardLatch
import org.junit.{ Test, Before, After }
import java.util.concurrent.{ ScheduledFuture, ConcurrentLinkedQueue, CountDownLatch, TimeUnit }

class SchedulerSpec extends JUnitSuite {
  private val futures = new ConcurrentLinkedQueue[ScheduledFuture[AnyRef]]()

  def collectFuture(f: ⇒ ScheduledFuture[AnyRef]): ScheduledFuture[AnyRef] = {
    val future = f
    futures.add(future)
    future
  }

  @Before
  def beforeEach {
    EventHandler.notify(Mute(EventFilter[Exception]("CRASH")))
  }

  @After
  def afterEach {
    while (futures.peek() ne null) { Option(futures.poll()).foreach(_.cancel(true)) }
    Actor.registry.local.shutdownAll
    EventHandler.start()
  }

  @Test
  def schedulerShouldScheduleMoreThanOnce = {

    case object Tick
    val countDownLatch = new CountDownLatch(3)
    val tickActor = actorOf(new Actor {
      def receive = { case Tick ⇒ countDownLatch.countDown() }
    })
    // run every 50 millisec
    collectFuture(Scheduler.schedule(tickActor, Tick, 0, 50, TimeUnit.MILLISECONDS))

    // after max 1 second it should be executed at least the 3 times already
    assert(countDownLatch.await(1, TimeUnit.SECONDS))

    val countDownLatch2 = new CountDownLatch(3)

    collectFuture(Scheduler.schedule(() ⇒ countDownLatch2.countDown(), 0, 50, TimeUnit.MILLISECONDS))

    // after max 1 second it should be executed at least the 3 times already
    assert(countDownLatch2.await(1, TimeUnit.SECONDS))
  }

  @Test
  def schedulerShouldScheduleOnce = {
    case object Tick
    val countDownLatch = new CountDownLatch(3)
    val tickActor = actorOf(new Actor {
      def receive = { case Tick ⇒ countDownLatch.countDown() }
    })
    // run every 50 millisec
    collectFuture(Scheduler.scheduleOnce(tickActor, Tick, 50, TimeUnit.MILLISECONDS))
    collectFuture(Scheduler.scheduleOnce(() ⇒ countDownLatch.countDown(), 50, TimeUnit.MILLISECONDS))

    // after 1 second the wait should fail
    assert(countDownLatch.await(1, TimeUnit.SECONDS) == false)
    // should still be 1 left
    assert(countDownLatch.getCount == 1)
  }

  /**
   * ticket #372
   */
  @Test
  def schedulerShouldntCreateActors = {
    object Ping
    val ticks = new CountDownLatch(1000)
    val actor = actorOf(new Actor {
      def receive = { case Ping ⇒ ticks.countDown }
    })
    val numActors = Actor.registry.local.actors.length
    (1 to 1000).foreach(_ ⇒ collectFuture(Scheduler.scheduleOnce(actor, Ping, 1, TimeUnit.MILLISECONDS)))
    assert(ticks.await(10, TimeUnit.SECONDS))
    assert(Actor.registry.local.actors.length === numActors)
  }

  /**
   * ticket #372
   */
  @Test
  def schedulerShouldBeCancellable = {
    object Ping
    val ticks = new CountDownLatch(1)

    val actor = actorOf(new Actor {
      def receive = { case Ping ⇒ ticks.countDown() }
    })

    (1 to 10).foreach { i ⇒
      val future = collectFuture(Scheduler.scheduleOnce(actor, Ping, 1, TimeUnit.SECONDS))
      future.cancel(true)
    }
    assert(ticks.await(3, TimeUnit.SECONDS) == false) //No counting down should've been made
  }

  /**
   * ticket #307
   */
  @Test
  def actorRestartShouldPickUpScheduleAgain = {

    object Ping
    object Crash

    val restartLatch = new StandardLatch
    val pingLatch = new CountDownLatch(6)

    val actor = actorOf(new Actor {
      def receive = {
        case Ping  ⇒ pingLatch.countDown()
        case Crash ⇒ throw new Exception("CRASH")
      }

      override def postRestart(reason: Throwable) = restartLatch.open
    })

    Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 1000),
        Supervise(
          actor,
          Permanent)
          :: Nil))

    collectFuture(Scheduler.schedule(actor, Ping, 500, 500, TimeUnit.MILLISECONDS))
    // appx 2 pings before crash
    collectFuture(Scheduler.scheduleOnce(actor, Crash, 1000, TimeUnit.MILLISECONDS))

    assert(restartLatch.tryAwait(2, TimeUnit.SECONDS))
    // should be enough time for the ping countdown to recover and reach 6 pings
    assert(pingLatch.await(4, TimeUnit.SECONDS))
  }
}
