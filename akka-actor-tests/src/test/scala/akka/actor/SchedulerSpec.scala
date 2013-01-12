package akka.actor

import language.postfixOps
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.duration._
import java.util.concurrent.{ CountDownLatch, ConcurrentLinkedQueue, TimeUnit }
import akka.testkit._
import scala.concurrent.Await
import akka.pattern.ask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeoutException
import scala.util.Random
import scala.util.control.NonFatal

object SchedulerSpec {
  val testConf = ConfigFactory.parseString("""
    akka.scheduler.ticks-per-wheel = 32
  """).withFallback(AkkaSpec.testConf)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SchedulerSpec extends AkkaSpec(SchedulerSpec.testConf) with BeforeAndAfterEach with DefaultTimeout with ImplicitSender {
  private val cancellables = new ConcurrentLinkedQueue[Cancellable]()
  import system.dispatcher

  def collectCancellable(c: Cancellable): Cancellable = {
    cancellables.add(c)
    c
  }

  "A Scheduler" must {

    "schedule more than once" in {
      case object Tick
      case object Tock

      val tickActor, tickActor2 = system.actorOf(Props(new Actor {
        var ticks = 0
        def receive = {
          case Tick ⇒
            if (ticks < 3) {
              sender ! Tock
              ticks += 1
            }
        }
      }))
      // run every 50 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds, tickActor, Tick))

      // after max 1 second it should be executed at least the 3 times already
      expectMsg(Tock)
      expectMsg(Tock)
      expectMsg(Tock)
      expectNoMsg(500 millis)

      collectCancellable(system.scheduler.schedule(0 milliseconds, 50 milliseconds)(tickActor2 ! Tick))

      // after max 1 second it should be executed at least the 3 times already
      expectMsg(Tock)
      expectMsg(Tock)
      expectMsg(Tock)
      expectNoMsg(500 millis)
    }

    "stop continuous scheduling if the receiving actor has been terminated" taggedAs TimingTest in {
      val actor = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender ! x } }))

      // run immediately and then every 100 milliseconds
      collectCancellable(system.scheduler.schedule(0 milliseconds, 100 milliseconds, actor, "msg"))
      expectMsg("msg")

      // stop the actor and, hence, the continuous messaging from happening
      system stop actor

      expectNoMsg(500 millis)
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
      for (_ ← 1 to 10) system.scheduler.scheduleOnce(1 second, testActor, "fail").cancel()

      expectNoMsg(2 seconds)
    }

    "be cancellable during initial delay" taggedAs TimingTest in {
      val ticks = new AtomicInteger

      val initialDelay = 200.milliseconds.dilated
      val delay = 10.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      Thread.sleep(10.milliseconds.dilated.toMillis)
      timeout.cancel()
      Thread.sleep((initialDelay + 100.milliseconds.dilated).toMillis)

      ticks.get must be(0)
    }

    "be cancellable after initial delay" taggedAs TimingTest in {
      val ticks = new AtomicInteger

      val initialDelay = 20.milliseconds.dilated
      val delay = 200.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      Thread.sleep((initialDelay + 100.milliseconds.dilated).toMillis)
      timeout.cancel()
      Thread.sleep((delay + 100.milliseconds.dilated).toMillis)

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
        def receive = { case Msg ⇒ ticks.countDown() }
      }))

      val startTime = System.nanoTime()
      collectCancellable(system.scheduler.schedule(1 second, 300 milliseconds, actor, Msg))
      Await.ready(ticks, 3 seconds)

      (System.nanoTime() - startTime).nanos.toMillis must be(1800L plusOrMinus 199)
    }

    "adjust for scheduler inaccuracy" taggedAs TimingTest in {
      val startTime = System.nanoTime
      val n = 33
      val latch = new TestLatch(n)
      system.scheduler.schedule(150.millis, 150.millis) { latch.countDown() }
      Await.ready(latch, 6.seconds)
      // Rate
      n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis must be(6.66 plusOrMinus 0.4)
    }

    "not be affected by long running task" taggedAs TimingTest in {
      val startTime = System.nanoTime
      val n = 22
      val latch = new TestLatch(n)
      system.scheduler.schedule(225.millis, 225.millis) {
        Thread.sleep(80)
        latch.countDown()
      }
      Await.ready(latch, 6.seconds)
      // Rate
      n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis must be(4.4 plusOrMinus 0.3)
    }

    "survive being stressed without cancellation" taggedAs TimingTest in {
      val r = Random
      val N = 100000
      for (_ ← 1 to N) {
        val next = r.nextInt(3000)
        val now = System.nanoTime
        system.scheduler.scheduleOnce(next.millis) {
          val stop = System.nanoTime
          testActor ! (stop - now - next * 1000000l)
        }
      }
      val latencies = within(5.seconds) {
        for (i ← 1 to N) yield try expectMsgType[Long] catch {
          case NonFatal(e) ⇒ println(s"failed expecting the $i-th latency"); throw e
        }
      }
      val histogram = latencies groupBy (_ / 100000000l)
      for (k ← histogram.keys.toSeq.sorted) {
        println(f"${k * 100}%3d: ${histogram(k).size}")
      }
    }

    "survive being stressed with cancellation" taggedAs TimingTest in {
      val r = Random
      val N = 1000000
      val tasks = for (_ ← 1 to N) yield {
        val next = r.nextInt(3000)
        val now = System.nanoTime
        system.scheduler.scheduleOnce(next.millis) {
          val stop = System.nanoTime
          testActor ! (stop - now - next * 1000000l)
        }
      }
      // get somewhat into the middle of things
      Thread.sleep(500)
      val cancellations = for (t ← tasks) yield {
        t.cancel()
        if (t.isCancelled) 1 else 0
      }
      val cancelled = cancellations.sum
      println(cancelled)
      val latencies = within(5.seconds) {
        for (i ← 1 to (N - cancelled)) yield try expectMsgType[Long] catch {
          case NonFatal(e) ⇒ println(s"failed expecting the $i-th latency"); throw e
        }
      }
      val histogram = latencies groupBy (_ / 100000000l)
      for (k ← histogram.keys.toSeq.sorted) {
        println(f"${k * 100}%3d: ${histogram(k).size}")
      }
      expectNoMsg(1.second)
    }
  }

  "A HashedWheelTimer" must {

    "not mess up long timeouts" taggedAs LongRunningTest in {
      val longish = Long.MaxValue.nanos
      val barrier = TestLatch()
      import system.dispatcher
      val job = system.scheduler.scheduleOnce(longish)(barrier.countDown())
      intercept[TimeoutException] {
        // this used to fire after 46 seconds due to wrap-around
        Await.ready(barrier, 90 seconds)
      }
      job.cancel()
    }

    "handle timeouts equal to multiple of wheel period" taggedAs TimingTest in {
      val timeout = 3200 milliseconds
      val barrier = TestLatch()
      import system.dispatcher
      val job = system.scheduler.scheduleOnce(timeout)(barrier.countDown())
      try {
        Await.ready(barrier, 5000 milliseconds)
      } finally {
        job.cancel()
      }
    }
  }
}
