package akka.actor

import org.scalatest.BeforeAndAfterEach
import org.multiverse.api.latches.StandardLatch
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import akka.util.duration._
import java.util.concurrent.{ CountDownLatch, ConcurrentLinkedQueue, TimeUnit }
import akka.testkit.DefaultTimeout

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SchedulerSpec extends AkkaSpec with BeforeAndAfterEach with DefaultTimeout {
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
      val tickActor = system.actorOf(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      })
      // run every 50 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds, tickActor, Tick))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch.await(1, TimeUnit.SECONDS))

      val countDownLatch2 = new CountDownLatch(3)

      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds)(countDownLatch2.countDown()))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch2.await(2, TimeUnit.SECONDS))
    }

    "should stop continuous scheduling if the receiving actor has been terminated" in {
      // run immediately and then every 100 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 100 milliseconds, testActor, "msg"))

      // stop the actor and, hence, the continuous messaging from happening
      testActor ! PoisonPill

      expectNoMsg(500 milliseconds)
    }

    "schedule once" in {
      case object Tick
      val countDownLatch = new CountDownLatch(3)
      val tickActor = system.actorOf(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      })

      // run after 300 millisec
      collectCancellable(system.scheduler.scheduleOnce(300 milliseconds, tickActor, Tick))
      collectCancellable(system.scheduler.scheduleOnce(300 milliseconds)(countDownLatch.countDown()))

      // should not be run immediately
      assert(countDownLatch.await(100, TimeUnit.MILLISECONDS) == false)
      countDownLatch.getCount must be(3)

      // after 1 second the wait should fail
      assert(countDownLatch.await(1, TimeUnit.SECONDS) == false)
      // should still be 1 left
      countDownLatch.getCount must be(1)
    }

    /**
     * ticket #372
     */
    "be cancellable" in {
      object Ping
      val ticks = new CountDownLatch(1)

      val actor = system.actorOf(new Actor {
        def receive = { case Ping ⇒ ticks.countDown() }
      })

      (1 to 10).foreach { i ⇒
        val timeout = collectCancellable(system.scheduler.scheduleOnce(1 second, actor, Ping))
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

      val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, 1000)))
      val props = Props(new Actor {
        def receive = {
          case Ping  ⇒ pingLatch.countDown()
          case Crash ⇒ throw new Exception("CRASH")
        }

        override def postRestart(reason: Throwable) = restartLatch.open
      })
      val actor = (supervisor ? props).as[ActorRef].get

      collectCancellable(system.scheduler.schedule(500 milliseconds, 500 milliseconds, actor, Ping))
      // appx 2 pings before crash
      EventFilter[Exception]("CRASH", occurrences = 1) intercept {
        collectCancellable(system.scheduler.scheduleOnce(1000 milliseconds, actor, Crash))
      }

      assert(restartLatch.tryAwait(2, TimeUnit.SECONDS))
      // should be enough time for the ping countdown to recover and reach 6 pings
      assert(pingLatch.await(4, TimeUnit.SECONDS))
    }

    "never fire prematurely" in {
      val ticks = new CountDownLatch(300)

      case class Msg(ts: Long)

      val actor = system.actorOf(new Actor {
        def receive = {
          case Msg(ts) ⇒
            val now = System.nanoTime
            // Make sure that no message has been dispatched before the scheduled time (10ms = 10000000ns) has occurred
            if (now - ts < 10000000) throw new RuntimeException("Interval is too small: " + (now - ts))
            ticks.countDown()
        }
      })

      (1 to 300).foreach { i ⇒
        collectCancellable(system.scheduler.scheduleOnce(10 milliseconds, actor, Msg(System.nanoTime)))
        Thread.sleep(5)
      }

      assert(ticks.await(3, TimeUnit.SECONDS) == true)
    }

    "schedule with different initial delay and frequency" in {
      val ticks = new CountDownLatch(3)

      case object Msg

      val actor = system.actorOf(new Actor {
        def receive = {
          case Msg ⇒ ticks.countDown()
        }
      })

      val startTime = System.nanoTime()
      val cancellable = system.scheduler.schedule(1 second, 100 milliseconds, actor, Msg)
      ticks.await(3, TimeUnit.SECONDS)
      val elapsedTimeMs = (System.nanoTime() - startTime) / 1000000

      assert(elapsedTimeMs > 1200)
      assert(elapsedTimeMs < 1500) // the precision is not ms exact
      cancellable.cancel()
    }
  }
}
