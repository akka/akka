/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps
import java.io.Closeable
import java.util.concurrent._
import atomic.{ AtomicReference, AtomicInteger }
import scala.concurrent.{ future, Await, ExecutionContext }
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Try
import scala.util.control.NonFatal
import org.scalatest.BeforeAndAfterEach
import com.typesafe.config.{ Config, ConfigFactory }
import akka.pattern.ask
import akka.testkit._

object SchedulerSpec {
  val testConf = ConfigFactory.parseString("""
    akka.scheduler.implementation = akka.actor.DefaultScheduler
    akka.scheduler.ticks-per-wheel = 32
  """).withFallback(AkkaSpec.testConf)

  val testConfRevolver = ConfigFactory.parseString("""
    akka.scheduler.implementation = akka.actor.LightArrayRevolverScheduler
  """).withFallback(testConf)
}

trait SchedulerSpec extends BeforeAndAfterEach with DefaultTimeout with ImplicitSender { this: AkkaSpec ⇒
  import system.dispatcher

  def collectCancellable(c: Cancellable): Cancellable

  "A Scheduler" must {

    "schedule more than once" taggedAs TimingTest in {
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

    "schedule once" taggedAs TimingTest in {
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
    "be cancellable" taggedAs TimingTest in {
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

      val initialDelay = 90.milliseconds.dilated
      val delay = 500.milliseconds.dilated
      val timeout = collectCancellable(system.scheduler.schedule(initialDelay, delay) {
        ticks.incrementAndGet()
      })
      Thread.sleep((initialDelay + 200.milliseconds.dilated).toMillis)
      timeout.cancel()
      Thread.sleep((delay + 100.milliseconds.dilated).toMillis)

      ticks.get must be(1)
    }

    "be canceled if cancel is performed before execution" in {
      val task = collectCancellable(system.scheduler.scheduleOnce(10 seconds)(()))
      task.cancel() must be(true)
      task.isCancelled must be(true)
      task.cancel() must be(false)
      task.isCancelled must be(true)
    }

    "not be canceled if cancel is performed after execution" in {
      val latch = TestLatch(1)
      val task = collectCancellable(system.scheduler.scheduleOnce(10 millis)(latch.countDown()))
      Await.ready(latch, remaining)
      task.cancel() must be(false)
      task.isCancelled must be(false)
      task.cancel() must be(false)
      task.isCancelled must be(false)
    }

    /**
     * ticket #307
     */
    "pick up schedule after actor restart" taggedAs TimingTest in {

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

    "never fire prematurely" taggedAs TimingTest in {
      val ticks = new TestLatch(300)

      case class Msg(ts: Long)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case Msg(ts) ⇒
            val now = System.nanoTime
            // Make sure that no message has been dispatched before the scheduled time (10ms) has occurred
            if (now - ts < 5.millis.toNanos) throw new RuntimeException("Interval is too small: " + (now - ts))
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

      // LARS is a bit more aggressive in scheduling recurring tasks at the right
      // frequency and may execute them a little earlier; the actual expected timing
      // is 1599ms on a fast machine or 1699ms on a loaded one (plus some room for jenkins)
      (System.nanoTime() - startTime).nanos.toMillis must be(1750L plusOrMinus 250)
    }

    "adjust for scheduler inaccuracy" taggedAs TimingTest in {
      val startTime = System.nanoTime
      val n = 333
      val latch = new TestLatch(n)
      system.scheduler.schedule(15.millis, 15.millis) { latch.countDown() }
      Await.ready(latch, 6.seconds)
      // Rate
      n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis must be(66.6 plusOrMinus 4)
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

    "survive being stressed without cancellation" taggedAs TimingTest in {
      val r = ThreadLocalRandom.current()
      val N = 100000
      for (_ ← 1 to N) {
        val next = r.nextInt(3000)
        val now = System.nanoTime
        system.scheduler.scheduleOnce(next.millis) {
          val stop = System.nanoTime
          testActor ! (stop - now - next * 1000000L)
        }
      }
      val latencies = within(10.seconds) {
        for (i ← 1 to N) yield try expectMsgType[Long] catch {
          case NonFatal(e) ⇒ throw new Exception(s"failed expecting the $i-th latency", e)
        }
      }
      val histogram = latencies groupBy (_ / 100000000L)
      for (k ← histogram.keys.toSeq.sorted) {
        system.log.info(f"${k * 100}%3d: ${histogram(k).size}")
      }
    }
  }
}

class DefaultSchedulerSpec extends AkkaSpec(SchedulerSpec.testConf) with SchedulerSpec {
  private val cancellables = new ConcurrentLinkedQueue[Cancellable]()

  def collectCancellable(c: Cancellable): Cancellable = {
    cancellables.add(c)
    c
  }

  override def afterEach {
    while (cancellables.peek() ne null) {
      for (c ← Option(cancellables.poll())) {
        c.cancel()
      }
    }
  }

}

class LightArrayRevolverSchedulerSpec extends AkkaSpec(SchedulerSpec.testConfRevolver) with SchedulerSpec {

  def collectCancellable(c: Cancellable): Cancellable = c

  "A LightArrayRevolverScheduler" must {

    "survive being stressed with cancellation" taggedAs TimingTest in {
      import system.dispatcher
      val r = ThreadLocalRandom.current
      val N = 1000000
      val tasks = for (_ ← 1 to N) yield {
        val next = r.nextInt(3000)
        val now = System.nanoTime
        system.scheduler.scheduleOnce(next.millis) {
          val stop = System.nanoTime
          testActor ! (stop - now - next * 1000000L)
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
      val latencies = within(10.seconds) {
        for (i ← 1 to (N - cancelled)) yield try expectMsgType[Long] catch {
          case NonFatal(e) ⇒ throw new Exception(s"failed expecting the $i-th latency", e)
        }
      }
      val histogram = latencies groupBy (_ / 100000000L)
      for (k ← histogram.keys.toSeq.sorted) {
        system.log.info(f"${k * 100}%3d: ${histogram(k).size}")
      }
      expectNoMsg(1.second)
    }

    "survive vicious enqueueing" in {
      withScheduler(config = ConfigFactory.parseString("akka.scheduler.ticks-per-wheel=2")) { (sched, driver) ⇒
        import driver._
        import system.dispatcher
        val counter = new AtomicInteger
        val terminated = future {
          var rounds = 0
          while (Try(sched.scheduleOnce(Duration.Zero)(())(localEC)).isSuccess) {
            Thread.sleep(1)
            driver.wakeUp(step)
            rounds += 1
          }
          rounds
        }
        def delay = if (ThreadLocalRandom.current.nextBoolean) step * 2 else step
        val N = 1000000
        (1 to N) foreach (_ ⇒ sched.scheduleOnce(delay)(counter.incrementAndGet()))
        sched.close()
        Await.result(terminated, 3.seconds.dilated) must be > 10
        awaitCond(counter.get == N)
      }
    }

    "execute multiple jobs at once when expiring multiple buckets" in {
      withScheduler() { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        val start = step / 2
        (0 to 3) foreach (i ⇒ sched.scheduleOnce(start + step * i, testActor, "hello"))
        expectNoMsg(step)
        wakeUp(step)
        expectWait(step)
        wakeUp(step * 4 + step / 2)
        expectWait(step / 2)
        (0 to 3) foreach (_ ⇒ expectMsg(Duration.Zero, "hello"))
      }
    }

    "correctly wrap around wheel rounds" in {
      withScheduler(config = ConfigFactory.parseString("akka.scheduler.ticks-per-wheel=2")) { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        val start = step / 2
        (0 to 3) foreach (i ⇒ sched.scheduleOnce(start + step * i, probe.ref, "hello"))
        probe.expectNoMsg(step)
        wakeUp(step)
        expectWait(step)
        // the following are no for-comp to see which iteration fails
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        probe.expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectWait(step)
      }
    }

    "correctly execute jobs when clock wraps around" in {
      withScheduler(Long.MaxValue - 200000000L) { (sched, driver) ⇒
        implicit def ec = localEC
        import driver._
        val start = step / 2
        (0 to 3) foreach (i ⇒ sched.scheduleOnce(start + step * i, testActor, "hello"))
        expectNoMsg(step)
        wakeUp(step)
        expectWait(step)
        // the following are no for-comp to see which iteration fails
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectMsg("hello")
        expectWait(step)
        wakeUp(step)
        expectWait(step)
      }
    }

    "reliably reject jobs when shutting down" in {
      withScheduler() { (sched, driver) ⇒
        import system.dispatcher
        val counter = new AtomicInteger
        future { Thread.sleep(5); driver.close(); sched.close() }
        val headroom = 200
        var overrun = headroom
        val cap = 1000000
        val (success, failure) = Iterator
          .continually(Try(sched.scheduleOnce(100.millis)(counter.incrementAndGet())))
          .take(cap)
          .takeWhile(_.isSuccess || { overrun -= 1; overrun >= 0 })
          .partition(_.isSuccess)
        val s = success.size
        s must be < cap
        awaitCond(s == counter.get, message = s"$s was not ${counter.get}")
        failure.size must be === headroom
      }
    }
  }

  trait Driver {
    def wakeUp(d: FiniteDuration): Unit
    def expectWait(): FiniteDuration
    def expectWait(d: FiniteDuration) { expectWait() must be(d) }
    def probe: TestProbe
    def step: FiniteDuration
    def close(): Unit
  }

  val localEC = new ExecutionContext {
    def execute(runnable: Runnable) { runnable.run() }
    def reportFailure(t: Throwable) { t.printStackTrace() }
  }

  def withScheduler(start: Long = 0L, config: Config = ConfigFactory.empty)(thunk: (Scheduler with Closeable, Driver) ⇒ Unit): Unit = {
    import akka.actor.{ LightArrayRevolverScheduler ⇒ LARS }
    val lbq = new AtomicReference[LinkedBlockingQueue[Long]](new LinkedBlockingQueue[Long])
    val prb = TestProbe()
    val tf = system.asInstanceOf[ActorSystemImpl].threadFactory
    val sched =
      new { @volatile var time = start } with LARS(config.withFallback(system.settings.config), log, tf) {
        override protected def clock(): Long = {
          // println(s"clock=$time")
          time
        }
        override protected def getShutdownTimeout: FiniteDuration = super.getShutdownTimeout.dilated

        override protected def waitNanos(ns: Long): Unit = {
          // println(s"waiting $ns")
          prb.ref ! ns
          try time += (lbq.get match {
            case q: LinkedBlockingQueue[Long] ⇒ q.take()
            case _                            ⇒ 0L
          })
          catch {
            case _: InterruptedException ⇒ Thread.currentThread.interrupt()
          }
        }
      }
    val driver = new Driver {
      def wakeUp(d: FiniteDuration) = lbq.get match {
        case q: LinkedBlockingQueue[Long] ⇒ q.offer(d.toNanos)
        case _                            ⇒
      }
      def expectWait(): FiniteDuration = probe.expectMsgType[Long].nanos
      def probe = prb
      def step = sched.TickDuration
      def close() = lbq.getAndSet(null) match {
        case q: LinkedBlockingQueue[Long] ⇒ q.offer(0L)
        case _                            ⇒
      }
    }
    driver.expectWait()
    try thunk(sched, driver)
    catch {
      case NonFatal(ex) ⇒
        try {
          driver.close()
          sched.close()
        } catch { case _: Exception ⇒ }
        throw ex
    }
    driver.close()
    sched.close()
  }

}
