package akka.actor

import org.scalatest.BeforeAndAfterEach
import org.multiverse.api.latches.StandardLatch
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import akka.util.duration._
import java.util.concurrent.{ CountDownLatch, ConcurrentLinkedQueue, TimeUnit }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SchedulerSpec extends AkkaSpec with BeforeAndAfterEach {
  private val cancellables = new ConcurrentLinkedQueue[Cancellable]()

  def collectCancellable(c: Cancellable): Cancellable = {
    cancellables.add(c)
    c
  }

  override def afterEach {
    while (cancellables.peek() ne null) { Option(cancellables.poll()).foreach(_.cancel()) }
  }

  "A Scheduler" must {

    "schedule more than once" in {
      case object Tick
      val countDownLatch = new CountDownLatch(3)
      val tickActor = actorOf(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      })
      // run every 50 millisec
      collectCancellable(system.scheduler.schedule(tickActor, Tick, 0 milliseconds, 50 milliseconds))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch.await(1, TimeUnit.SECONDS))

      val countDownLatch2 = new CountDownLatch(3)

      collectCancellable(system.scheduler.schedule(() ⇒ countDownLatch2.countDown(), 0 milliseconds, 50 milliseconds))

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
      collectCancellable(system.scheduler.scheduleOnce(tickActor, Tick, 50 milliseconds))
      collectCancellable(system.scheduler.scheduleOnce(() ⇒ countDownLatch.countDown(), 50 milliseconds))

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
    //   val numActors = system.registry.local.actors.length
    //   (1 to 1000).foreach(_ ⇒ collectFuture(Scheduler.scheduleOnce(actor, Ping, 1, TimeUnit.MILLISECONDS)))
    //   assert(ticks.await(10, TimeUnit.SECONDS))
    //   assert(system.registry.local.actors.length === numActors)
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
        val timeout = collectCancellable(system.scheduler.scheduleOnce(actor, Ping, 1 second))
        timeout.cancel()
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

      collectCancellable(system.scheduler.schedule(actor, Ping, 500 milliseconds, 500 milliseconds))
      // appx 2 pings before crash
      EventFilter[Exception]("CRASH", occurrences = 1) intercept {
        collectCancellable(system.scheduler.scheduleOnce(actor, Crash, 1000 milliseconds))
      }

      assert(restartLatch.tryAwait(2, TimeUnit.SECONDS))
      // should be enough time for the ping countdown to recover and reach 6 pings
      assert(pingLatch.await(4, TimeUnit.SECONDS))
    }

    "never fire prematurely" in {
      val ticks = new CountDownLatch(300)

      case class Msg(ts: Long)

      val actor = actorOf(new Actor {
        def receive = {
          case Msg(ts) ⇒
            val now = System.nanoTime
            // Make sure that no message has been dispatched before the scheduled time (10ms = 10000000ns) has occurred
            if (now - ts < 10000000) throw new RuntimeException("Interval is too small: " + (now - ts))
            ticks.countDown()
        }
      })

      (1 to 300).foreach { i ⇒
        collectCancellable(system.scheduler.scheduleOnce(actor, Msg(System.nanoTime), 10 milliseconds))
        Thread.sleep(5)
      }

      assert(ticks.await(3, TimeUnit.SECONDS) == true)
    }

    "schedule with different initial delay and frequency" in {
      val ticks = new CountDownLatch(3)

      case object Msg

      val actor = actorOf(new Actor {
        def receive = {
          case Msg ⇒ ticks.countDown()
        }
      })

      val startTime = System.nanoTime()
      val cancellable = system.scheduler.schedule(actor, Msg, 1 second, 100 milliseconds)
      ticks.await(3, TimeUnit.SECONDS)
      val elapsedTimeMs = (System.nanoTime() - startTime) / 1000000

      assert(elapsedTimeMs > 1200)
      assert(elapsedTimeMs < 1500) // the precision is not ms exact
      cancellable.cancel()
    }
  }
}
