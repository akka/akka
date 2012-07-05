/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.dispatch

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.scalatest.Assertions._
import akka.testkit.Testing
import akka.dispatch._
import akka.actor.{ ActorRef, Actor }
import akka.actor.Actor._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import akka.actor.dispatch.ActorModelSpec.MessageDispatcherInterceptor
import akka.util.{ Duration, Switch }
import org.multiverse.api.latches.StandardLatch

object ActorModelSpec {

  sealed trait ActorModelMessage
  case class TryReply(expect: Any) extends ActorModelMessage
  case class Reply(expect: Any) extends ActorModelMessage
  case class Forward(to: ActorRef, msg: Any) extends ActorModelMessage
  case class CountDown(latch: CountDownLatch) extends ActorModelMessage
  case class Increment(counter: AtomicLong) extends ActorModelMessage
  case class Await(latch: CountDownLatch) extends ActorModelMessage
  case class Meet(acknowledge: CountDownLatch, waitFor: CountDownLatch) extends ActorModelMessage
  case class CountDownNStop(latch: CountDownLatch) extends ActorModelMessage
  case class Wait(time: Long) extends ActorModelMessage
  case class WaitAck(time: Long, latch: CountDownLatch) extends ActorModelMessage
  case object Restart extends ActorModelMessage

  val Ping = "Ping"
  val Pong = "Pong"

  class DispatcherActor(dispatcher: MessageDispatcherInterceptor) extends Actor {
    self.dispatcher = dispatcher.asInstanceOf[MessageDispatcher]

    private val busy = new Switch(false)

    def ack {
      if (!busy.switchOn()) {
        throw new Exception("isolation violated")
      } else {
        dispatcher.getStats(self).msgsProcessed.incrementAndGet()
      }
    }

    override def postRestart(reason: Throwable) {
      dispatcher.getStats(self).restarts.incrementAndGet()
    }

    def receive = {
      case Await(latch)      ⇒ ack; latch.await(); busy.switchOff()
      case Meet(sign, wait)  ⇒ ack; sign.countDown(); wait.await(); busy.switchOff()
      case Wait(time)        ⇒ ack; Thread.sleep(time); busy.switchOff()
      case WaitAck(time, l)  ⇒ ack; Thread.sleep(time); l.countDown(); busy.switchOff()
      case Reply(msg)        ⇒ ack; self.reply(msg); busy.switchOff()
      case TryReply(msg)     ⇒ ack; self.tryReply(msg); busy.switchOff()
      case Forward(to, msg)  ⇒ ack; to.forward(msg); busy.switchOff()
      case CountDown(latch)  ⇒ ack; latch.countDown(); busy.switchOff()
      case Increment(count)  ⇒ ack; count.incrementAndGet(); busy.switchOff()
      case CountDownNStop(l) ⇒ ack; l.countDown(); self.stop(); busy.switchOff()
      case Restart           ⇒ ack; busy.switchOff(); throw new Exception("Restart requested")
    }
  }

  class InterceptorStats {
    val suspensions = new AtomicLong(0)
    val resumes = new AtomicLong(0)
    val registers = new AtomicLong(0)
    val unregisters = new AtomicLong(0)
    val msgsReceived = new AtomicLong(0)
    val msgsProcessed = new AtomicLong(0)
    val restarts = new AtomicLong(0)
  }

  trait MessageDispatcherInterceptor extends MessageDispatcher {
    val stats = new ConcurrentHashMap[ActorRef, InterceptorStats]
    val starts = new AtomicLong(0)
    val stops = new AtomicLong(0)

    def getStats(actorRef: ActorRef) = {
      stats.putIfAbsent(actorRef, new InterceptorStats)
      stats.get(actorRef)
    }

    abstract override def suspend(actorRef: ActorRef) {
      super.suspend(actorRef)
      getStats(actorRef).suspensions.incrementAndGet()
    }

    abstract override def resume(actorRef: ActorRef) {
      super.resume(actorRef)
      getStats(actorRef).resumes.incrementAndGet()
    }

    protected[akka] abstract override def register(actorRef: ActorRef) {
      super.register(actorRef)
      getStats(actorRef).registers.incrementAndGet()
    }

    protected[akka] abstract override def unregister(actorRef: ActorRef) {
      super.unregister(actorRef)
      getStats(actorRef).unregisters.incrementAndGet()
    }

    protected[akka] abstract override def dispatch(invocation: MessageInvocation) {
      getStats(invocation.receiver).msgsReceived.incrementAndGet()
      super.dispatch(invocation)
    }

    protected[akka] abstract override def start() {
      super.start()
      starts.incrementAndGet()
    }

    protected[akka] abstract override def shutdown() {
      super.shutdown()
      stops.incrementAndGet()
    }
  }

  def assertDispatcher(dispatcher: MessageDispatcherInterceptor)(
    starts: Long = dispatcher.starts.get(),
    stops: Long = dispatcher.stops.get()) {
    assert(starts === dispatcher.starts.get(), "Dispatcher starts")
    assert(stops === dispatcher.stops.get(), "Dispatcher stops")
  }

  def assertCountDown(latch: CountDownLatch, wait: Long, hint: AnyRef) {
    assert(latch.await(wait, TimeUnit.MILLISECONDS) === true)
  }

  def assertNoCountDown(latch: CountDownLatch, wait: Long, hint: AnyRef) {
    assert(latch.await(wait, TimeUnit.MILLISECONDS) === false)
  }

  def statsFor(actorRef: ActorRef, dispatcher: MessageDispatcher = null) =
    dispatcher.asInstanceOf[MessageDispatcherInterceptor].getStats(actorRef)

  def assertRefDefaultZero(actorRef: ActorRef, dispatcher: MessageDispatcher = null)(
    suspensions: Long = 0,
    resumes: Long = 0,
    registers: Long = 0,
    unregisters: Long = 0,
    msgsReceived: Long = 0,
    msgsProcessed: Long = 0,
    restarts: Long = 0) {
    assertRef(actorRef, dispatcher)(
      suspensions,
      resumes,
      registers,
      unregisters,
      msgsReceived,
      msgsProcessed,
      restarts)
  }

  def assertRef(actorRef: ActorRef, dispatcher: MessageDispatcher = null)(
    suspensions: Long = statsFor(actorRef).suspensions.get(),
    resumes: Long = statsFor(actorRef).resumes.get(),
    registers: Long = statsFor(actorRef).registers.get(),
    unregisters: Long = statsFor(actorRef).unregisters.get(),
    msgsReceived: Long = statsFor(actorRef).msgsReceived.get(),
    msgsProcessed: Long = statsFor(actorRef).msgsProcessed.get(),
    restarts: Long = statsFor(actorRef).restarts.get()) {
    val stats = statsFor(actorRef, if (dispatcher eq null) actorRef.dispatcher else dispatcher)
    assert(stats.suspensions.get() === suspensions, "Suspensions")
    assert(stats.resumes.get() === resumes, "Resumes")
    assert(stats.registers.get() === registers, "Registers")
    assert(stats.unregisters.get() === unregisters, "Unregisters")
    assert(stats.msgsReceived.get() === msgsReceived, "Received")
    assert(stats.msgsProcessed.get() === msgsProcessed, "Processed")
    assert(stats.restarts.get() === restarts, "Restarts")
  }

  def await(condition: ⇒ Boolean)(withinMs: Long, intervalMs: Long = 25): Boolean = try {
    val until = System.currentTimeMillis() + withinMs
    while (System.currentTimeMillis() <= until) {
      try {
        if (condition) return true

        Thread.sleep(intervalMs)
      } catch { case e: InterruptedException ⇒ }
    }
    false
  }

  def newTestActor(implicit d: MessageDispatcherInterceptor) = actorOf(new DispatcherActor(d))
}

abstract class ActorModelSpec extends JUnitSuite {
  import ActorModelSpec._

  protected def newInterceptedDispatcher: MessageDispatcherInterceptor

  @Test
  def dispatcherShouldDynamicallyHandleItsOwnLifeCycle {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor
    assertDispatcher(dispatcher)(starts = 0, stops = 0)
    a.start()
    assertDispatcher(dispatcher)(starts = 1, stops = 0)
    a.stop()
    await(dispatcher.stops.get == 1)(withinMs = dispatcher.timeoutMs * 5)
    assertDispatcher(dispatcher)(starts = 1, stops = 1)
    assertRef(a, dispatcher)(
      suspensions = 0,
      resumes = 0,
      registers = 1,
      unregisters = 1,
      msgsReceived = 0,
      msgsProcessed = 0,
      restarts = 0)

    val futures = for (i ← 1 to 10) yield Future { i }
    await(dispatcher.stops.get == 2)(withinMs = dispatcher.timeoutMs * 5)
    assertDispatcher(dispatcher)(starts = 2, stops = 2)

    val a2 = newTestActor
    a2.start
    val futures2 = for (i ← 1 to 10) yield Future { i }

    await(dispatcher.starts.get == 3)(withinMs = dispatcher.timeoutMs * 5)
    assertDispatcher(dispatcher)(starts = 3, stops = 2)

    a2.stop
    await(dispatcher.stops.get == 3)(withinMs = dispatcher.timeoutMs * 5)
    assertDispatcher(dispatcher)(starts = 3, stops = 3)
  }

  @Test
  def dispatcherShouldProcessMessagesOneAtATime {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor
    val start, oneAtATime = new CountDownLatch(1)
    a.start()

    a ! CountDown(start)
    assertCountDown(start, Testing.testTime(3000), "Should process first message within 3 seconds")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, msgsProcessed = 1)

    a ! Wait(1000)
    a ! CountDown(oneAtATime)
    // in case of serialization violation, restart would happen instead of count down
    assertCountDown(oneAtATime, Testing.testTime(1500), "Processed message when allowed")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 3, msgsProcessed = 3)

    a.stop()
    assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 3, msgsProcessed = 3)
  }

  @Test
  def dispatcherShouldHandleQueueingFromMultipleThreads {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor
    val counter = new CountDownLatch(200)
    a.start()

    def start = spawn { for (i ← 1 to 20) { a ! WaitAck(1, counter) } }
    for (i ← 1 to 10) { start }
    assertCountDown(counter, Testing.testTime(3000), "Should process 200 messages")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 200, msgsProcessed = 200)

    a.stop()
  }

  def spawn(f: ⇒ Unit) = {
    val thread = new Thread { override def run { f } }
    thread.start()
    thread
  }

  @Test
  def dispatcherShouldProcessMessagesInParallel: Unit = {
    implicit val dispatcher = newInterceptedDispatcher
    val a, b = newTestActor.start()
    val aStart, aStop, bParallel = new CountDownLatch(1)

    a ! Meet(aStart, aStop)
    assertCountDown(aStart, Testing.testTime(3000), "Should process first message within 3 seconds")

    b ! CountDown(bParallel)
    assertCountDown(bParallel, Testing.testTime(3000), "Should process other actors in parallel")

    aStop.countDown()
    a.stop()
    b.stop()
    assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
    assertRefDefaultZero(b)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
  }

  @Test
  def dispatcherShouldSuspendAndResumeAFailingNonSupervisedPermanentActor {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor.start()
    val done = new CountDownLatch(1)
    a ! Restart
    a ! CountDown(done)
    assertCountDown(done, Testing.testTime(3000), "Should be suspended+resumed and done with next message within 3 seconds")
    a.stop()
    assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 2,
      msgsProcessed = 2, suspensions = 1, resumes = 1)
  }

  @Test
  def dispatcherShouldNotProcessMessagesForASuspendedActor {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor.start()
    val done = new CountDownLatch(1)
    dispatcher.suspend(a)
    a ! CountDown(done)
    assertNoCountDown(done, 1000, "Should not process messages while suspended")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, suspensions = 1)

    dispatcher.resume(a)
    assertCountDown(done, Testing.testTime(3000), "Should resume processing of messages when resumed")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, msgsProcessed = 1,
      suspensions = 1, resumes = 1)

    a.stop()
    assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1,
      suspensions = 1, resumes = 1)
  }

  @Test
  def dispatcherShouldHandleWavesOfActors {
    implicit val dispatcher = newInterceptedDispatcher

    def flood(num: Int) {
      val cachedMessage = CountDownNStop(new CountDownLatch(num))
      (1 to num) foreach { _ ⇒
        newTestActor.start() ! cachedMessage
      }
      assertCountDown(cachedMessage.latch, Testing.testTime(10000), "Should process " + num + " countdowns")
    }
    for (run ← 1 to 3) {
      flood(10000)
      await(dispatcher.stops.get == run)(withinMs = 10000)
      assertDispatcher(dispatcher)(starts = run, stops = run)
    }
  }
}

class ExecutorBasedEventDrivenDispatcherModelTest extends ActorModelSpec {
  def newInterceptedDispatcher =
    new ExecutorBasedEventDrivenDispatcher("foo") with MessageDispatcherInterceptor
}

class ExecutorBasedEventDrivenWorkStealingDispatcherModelTest extends ActorModelSpec {
  def newInterceptedDispatcher =
    new ExecutorBasedEventDrivenWorkStealingDispatcher("foo") with MessageDispatcherInterceptor
}
