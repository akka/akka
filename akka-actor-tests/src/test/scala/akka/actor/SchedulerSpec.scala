package akka.actor

import org.scalatest.BeforeAndAfterEach
import akka.util.duration._
import java.util.concurrent.{ CountDownLatch, ConcurrentLinkedQueue, TimeUnit }
import akka.testkit._
import akka.dispatch.Await
import akka.pattern.ask
import java.util.concurrent.atomic.AtomicInteger

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
      val tickActor = system.actorOf(Props(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      }))
      // run every 50 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds, tickActor, Tick))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch.await(2, TimeUnit.SECONDS))

      val countDownLatch2 = new CountDownLatch(3)

      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds)(countDownLatch2.countDown()))

      // after max 1 second it should be executed at least the 3 times already
      assert(countDownLatch2.await(2, TimeUnit.SECONDS))
    }

    "should stop continuous scheduling if the receiving actor has been terminated" taggedAs TimingTest in {
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒ testActor ! x
        }
      }))

      // run immediately and then every 100 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 100 milliseconds, actor, "msg"))
      expectNoMsg(1 second)

      // stop the actor and, hence, the continuous messaging from happening
      actor ! PoisonPill

      expectMsg("msg")
    }

    "schedule once" in {
      case object Tick
      val countDownLatch = new CountDownLatch(3)
      val tickActor = system.actorOf(Props(new Actor {
        def receive = { case Tick ⇒ countDownLatch.countDown() }
      }))

      // run after 300 millisec
      collectCancellable(system.scheduler.scheduleOnce(300 milliseconds, tickActor, Tick))
      collectCancellable(system.scheduler.scheduleOnce(300 milliseconds)(countDownLatch.countDown()))

      // should not be run immediately
      assert(countDownLatch.await(100, TimeUnit.MILLISECONDS) == false)
      countDownLatch.getCount must be(3)

      // after 1 second the wait should fail
      assert(countDownLatch.await(2, TimeUnit.SECONDS) == false)
      // should still be 1 left
      countDownLatch.getCount must be(1)
    }

    /**
     * ticket #372
     */
    "be cancellable" in {
      object Ping
      val ticks = new CountDownLatch(1)

      val actor = system.actorOf(Props(new Actor {
        def receive = { case Ping ⇒ ticks.countDown() }
      }))

      (1 to 10).foreach { i ⇒
        val timeout = collectCancellable(system.scheduler.scheduleOnce(1 second, actor, Ping))
        timeout.cancel()
      }

      assert(ticks.await(3, TimeUnit.SECONDS) == false) //No counting down should've been made
    }

    "be cancellable during initial delay" taggedAs TimingTest in {
      val ticks = new AtomicInteger

      val initialDelay = 200.milliseconds.dilated
      val delay = 10.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      10.milliseconds.dilated.sleep()
      timeout.cancel()
      (initialDelay + 100.milliseconds.dilated).sleep()

      ticks.get must be(0)
    }

    "be cancellable after initial delay" taggedAs TimingTest in {
      val ticks = new AtomicInteger

      val initialDelay = 20.milliseconds.dilated
      val delay = 200.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      (initialDelay + 100.milliseconds.dilated).sleep()
      timeout.cancel()
      (delay + 100.milliseconds.dilated).sleep()

      ticks.get must be(1)
    }

    /**
     * ticket #307
     */
    "pick up schedule after actor restart" in {

      object Ping
      object Crash

      val restartLatch = new TestLatch
      val pingLatch = new TestLatch(6)

      val supervisor = system.actorOf(Props(new Supervisor(AllForOneStrategy(3, 1 second)(List(classOf[Exception])))))
      val props = Props(new Actor {
        def receive = {
          case Ping  ⇒ pingLatch.countDown()
          case Crash ⇒ throw new Exception("CRASH")
        }

        override def postRestart(reason: Throwable) = restartLatch.open
      })
      val actor = Await.result((supervisor ? props).mapTo[ActorRef], timeout.duration)

      collectCancellable(system.scheduler.schedule(500 milliseconds, 500 milliseconds, actor, Ping))
      // appx 2 pings before crash
      EventFilter[Exception]("CRASH", occurrences = 1) intercept {
        collectCancellable(system.scheduler.scheduleOnce(1000 milliseconds, actor, Crash))
      }

      Await.ready(restartLatch, 2 seconds)
      // should be enough time for the ping countdown to recover and reach 6 pings
      Await.ready(pingLatch, 5 seconds)
    }

    "never fire prematurely" in {
      val ticks = new TestLatch(300)

      case class Msg(ts: Long)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case Msg(ts) ⇒
            val now = System.nanoTime
            // Make sure that no message has been dispatched before the scheduled time (10ms) has occurred
            if (now - ts < 10.millis.toNanos) throw new RuntimeException("Interval is too small: " + (now - ts))
            ticks.countDown()
        }
      }))

      (1 to 300).foreach { i ⇒
        collectCancellable(system.scheduler.scheduleOnce(10 milliseconds, actor, Msg(System.nanoTime)))
        Thread.sleep(5)
      }

      Await.ready(ticks, 3 seconds)
    }

    "schedule with different initial delay and frequency" taggedAs TimingTest in {
      val ticks = new TestLatch(3)

      case object Msg

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case Msg ⇒ ticks.countDown()
        }
      }))

      val startTime = System.nanoTime()
      val cancellable = system.scheduler.schedule(1 second, 300 milliseconds, actor, Msg)
      Await.ready(ticks, 3 seconds)
      val elapsedTimeMs = (System.nanoTime() - startTime) / 1000000

      assert(elapsedTimeMs > 1600)
      assert(elapsedTimeMs < 2000) // the precision is not ms exact
      cancellable.cancel()
    }
  }
}
