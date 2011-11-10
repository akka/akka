package akka.actor

import org.scalatest.BeforeAndAfterEach
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent.{ ScheduledFuture, ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SchedulerSpec extends AkkaSpec with BeforeAndAfterEach {
  private val futures = new ConcurrentLinkedQueue[ScheduledFuture[AnyRef]]()

  def collectFuture(f: ⇒ ScheduledFuture[AnyRef]): ScheduledFuture[AnyRef] = {
    val future = f
    futures.add(future)
    future
  }

  override def afterEach {
    while (futures.peek() ne null) { Option(futures.poll()).foreach(_.cancel(true)) }
  }

  "A Scheduler" must {

    "schedule more than once" in {
      case object Tick
      val countDownLatch = new CountDownLatch(3)
      val tickActor = actorOf(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      })
      // run every 50 millisec
      collectFuture(app.scheduler.schedule(tickActor, Tick, 0, 50, TimeUnit.MILLISECONDS))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch.await(1, TimeUnit.SECONDS))

      val countDownLatch2 = new CountDownLatch(3)

      collectFuture(app.scheduler.schedule(() ⇒ countDownLatch2.countDown(), 0, 50, TimeUnit.MILLISECONDS))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch2.await(2, TimeUnit.SECONDS))
    }

    "schedule once" in {
      case object Tick
      val countDownLatch = new CountDownLatch(3)
      val tickActor = actorOf(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      })
      // run every 50 millisec
      collectFuture(app.scheduler.scheduleOnce(tickActor, Tick, 50, TimeUnit.MILLISECONDS))
      collectFuture(app.scheduler.scheduleOnce(() ⇒ countDownLatch.countDown(), 50, TimeUnit.MILLISECONDS))

      // after 1 second the wait should fail
      assert(countDownLatch.await(2, TimeUnit.SECONDS) == false)
      // should still be 1 left
      assert(countDownLatch.getCount == 1)
    }

    /**
     * ticket #372
     * FIXME rewrite the test so that registry is not used
     */
    // "not create actors" in {
    //   object Ping
    //   val ticks = new CountDownLatch(1000)
    //   val actor = actorOf(new Actor {
    //     def receive = { case Ping ⇒ ticks.countDown }
    //   })
    //   val numActors = app.registry.local.actors.length
    //   (1 to 1000).foreach(_ ⇒ collectFuture(Scheduler.scheduleOnce(actor, Ping, 1, TimeUnit.MILLISECONDS)))
    //   assert(ticks.await(10, TimeUnit.SECONDS))
    //   assert(app.registry.local.actors.length === numActors)
    // }

    /**
     * ticket #372
     */
    "be cancellable" in {
      object Ping
      val ticks = new CountDownLatch(1)

      val actor = actorOf(new Actor {
        def receive = { case Ping ⇒ ticks.countDown() }
      })

      (1 to 10).foreach { i ⇒
        val future = collectFuture(app.scheduler.scheduleOnce(actor, Ping, 1, TimeUnit.SECONDS))
        future.cancel(true)
      }
      assert(ticks.await(3, TimeUnit.SECONDS) == false) //No counting down should've been made
    }

    /**
     * ticket #307
     */
    "pick up schedule after actor restart" in {
      object Ping
      object Crash

      val restartLatch = new StandardLatch
      val pingLatch = new CountDownLatch(6)

      val supervisor = actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, 1000)))
      val props = Props(new Actor {
        def receive = {
          case Ping  ⇒ pingLatch.countDown()
          case Crash ⇒ throw new Exception("CRASH")
        }

        override def postRestart(reason: Throwable) = restartLatch.open
      })
      val actor = (supervisor ? props).as[ActorRef].get

      collectFuture(app.scheduler.schedule(actor, Ping, 500, 500, TimeUnit.MILLISECONDS))
      // appx 2 pings before crash
      collectFuture(app.scheduler.scheduleOnce(actor, Crash, 1000, TimeUnit.MILLISECONDS))

      assert(restartLatch.tryAwait(2, TimeUnit.SECONDS))
      // should be enough time for the ping countdown to recover and reach 6 pings
      assert(pingLatch.await(4, TimeUnit.SECONDS))
    }
  }
}
