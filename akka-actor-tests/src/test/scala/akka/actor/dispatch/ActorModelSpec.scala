/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import akka.event.EventHandler
import org.scalatest.junit.JUnitSuite
import org.scalatest.Assertions._
import akka.testkit.{ Testing, filterEvents, EventFilter }
import akka.dispatch._
import akka.actor.Actor._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import akka.actor.dispatch.ActorModelSpec.MessageDispatcherInterceptor
import akka.util.Switch
import java.rmi.RemoteException
import org.junit.{ After, Test }
import akka.actor._

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

  case object Interrupt extends ActorModelMessage

  case object Restart extends ActorModelMessage

  case class ThrowException(e: Throwable) extends ActorModelMessage

  val Ping = "Ping"
  val Pong = "Pong"

  class DispatcherActor extends Actor {
    private val busy = new Switch(false)

    def interceptor = dispatcher.asInstanceOf[MessageDispatcherInterceptor]

    def ack {
      if (!busy.switchOn()) {
        throw new Exception("isolation violated")
      } else {
        interceptor.getStats(self).msgsProcessed.incrementAndGet()
      }
    }

    override def postRestart(reason: Throwable) {
      interceptor.getStats(self).restarts.incrementAndGet()
    }

    def receive = {
      case Await(latch)                 ⇒ ack; latch.await(); busy.switchOff()
      case Meet(sign, wait)             ⇒ ack; sign.countDown(); wait.await(); busy.switchOff()
      case Wait(time)                   ⇒ ack; Thread.sleep(time); busy.switchOff()
      case WaitAck(time, l)             ⇒ ack; Thread.sleep(time); l.countDown(); busy.switchOff()
      case Reply(msg)                   ⇒ ack; reply(msg); busy.switchOff()
      case TryReply(msg)                ⇒ ack; tryReply(msg); busy.switchOff()
      case Forward(to, msg)             ⇒ ack; to.forward(msg); busy.switchOff()
      case CountDown(latch)             ⇒ ack; latch.countDown(); busy.switchOff()
      case Increment(count)             ⇒ ack; count.incrementAndGet(); busy.switchOff()
      case CountDownNStop(l)            ⇒ ack; l.countDown(); self.stop(); busy.switchOff()
      case Restart                      ⇒ ack; busy.switchOff(); throw new Exception("Restart requested")
      case Interrupt                    ⇒ ack; busy.switchOff(); throw new InterruptedException("Ping!")
      case ThrowException(e: Throwable) ⇒ ack; busy.switchOff(); throw e
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

    abstract override def suspend(actor: ActorCell) {
      super.suspend(actor)
      getStats(actor.ref).suspensions.incrementAndGet()
    }

    abstract override def resume(actor: ActorCell) {
      super.resume(actor)
      getStats(actor.ref).resumes.incrementAndGet()
    }

    protected[akka] abstract override def register(actor: ActorCell) {
      super.register(actor)
      getStats(actor.ref).registers.incrementAndGet()
    }

    protected[akka] abstract override def unregister(actor: ActorCell) {
      super.unregister(actor)
      getStats(actor.ref).unregisters.incrementAndGet()
    }

    protected[akka] abstract override def dispatch(invocation: Envelope) {
      getStats(invocation.receiver.ref).msgsReceived.incrementAndGet()
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
    val stats = statsFor(actorRef, Option(dispatcher).getOrElse(actorRef.asInstanceOf[LocalActorRef].underlying.dispatcher))
    assert(stats.suspensions.get() === suspensions, "Suspensions")
    assert(stats.resumes.get() === resumes, "Resumes")
    assert(stats.registers.get() === registers, "Registers")
    assert(stats.unregisters.get() === unregisters, "Unregisters")
    assert(stats.msgsReceived.get() === msgsReceived, "Received")
    assert(stats.msgsProcessed.get() === msgsProcessed, "Processed")
    assert(stats.restarts.get() === restarts, "Restarts")
  }

  def await(condition: ⇒ Boolean)(withinMs: Long, intervalMs: Long = 25): Unit = try {
    val until = System.currentTimeMillis() + withinMs
    while (System.currentTimeMillis() <= until) {
      try {
        if (condition) return

        Thread.sleep(intervalMs)
      } catch {
        case e: InterruptedException ⇒
      }
    }
    assert(0 === 1, "await failed")
  }

  def newTestActor(implicit d: MessageDispatcherInterceptor) = actorOf(Props[DispatcherActor].withDispatcher(d))
}

abstract class ActorModelSpec extends JUnitSuite {

  import ActorModelSpec._

  protected def newInterceptedDispatcher: MessageDispatcherInterceptor

  @Test
  def dispatcherShouldDynamicallyHandleItsOwnLifeCycle {
    implicit val dispatcher = newInterceptedDispatcher
    assertDispatcher(dispatcher)(starts = 0, stops = 0)
    val a = newTestActor
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

    val futures = for (i ← 1 to 10) yield Future {
      i
    }
    await(dispatcher.stops.get == 2)(withinMs = dispatcher.timeoutMs * 5)
    assertDispatcher(dispatcher)(starts = 2, stops = 2)

    val a2 = newTestActor
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
    val start, oneAtATime = new CountDownLatch(1)
    val a = newTestActor

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
    val counter = new CountDownLatch(200)
    val a = newTestActor

    for (i ← 1 to 10) {
      spawn {
        for (i ← 1 to 20) {
          a ! WaitAck(1, counter)
        }
      }
    }
    assertCountDown(counter, Testing.testTime(3000), "Should process 200 messages")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 200, msgsProcessed = 200)

    a.stop()
  }

  def spawn(f: ⇒ Unit) {
    val thread = new Thread {
      override def run {
        try {
          f
        } catch {
          case e ⇒ EventHandler.error(e, this, "error in spawned thread")
        }
      }
    }
    thread.start()
  }

  @Test
  def dispatcherShouldProcessMessagesInParallel: Unit = {
    implicit val dispatcher = newInterceptedDispatcher
    val aStart, aStop, bParallel = new CountDownLatch(1)
    val a, b = newTestActor

    a ! Meet(aStart, aStop)
    assertCountDown(aStart, Testing.testTime(3000), "Should process first message within 3 seconds")

    b ! CountDown(bParallel)
    assertCountDown(bParallel, Testing.testTime(3000), "Should process other actors in parallel")

    aStop.countDown()

    a.stop
    b.stop

    while (a.isRunning && b.isRunning) {} //Busy wait for termination

    assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
    assertRefDefaultZero(b)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
  }

  @Test
  def dispatcherShouldSuspendAndResumeAFailingNonSupervisedPermanentActor {
    filterEvents(EventFilter[Exception]("Restart")) {
      implicit val dispatcher = newInterceptedDispatcher
      val a = newTestActor
      val done = new CountDownLatch(1)
      a ! Restart
      a ! CountDown(done)
      assertCountDown(done, Testing.testTime(3000), "Should be suspended+resumed and done with next message within 3 seconds")
      a.stop()
      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 2,
        msgsProcessed = 2, suspensions = 1, resumes = 1)
    }
  }

  @Test
  def dispatcherShouldNotProcessMessagesForASuspendedActor {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor.asInstanceOf[LocalActorRef]
    val done = new CountDownLatch(1)
    a.suspend
    a ! CountDown(done)
    assertNoCountDown(done, 1000, "Should not process messages while suspended")
    assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, suspensions = 1)

    a.resume
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
        newTestActor ! cachedMessage
      }
      assertCountDown(cachedMessage.latch, Testing.testTime(10000), "Should process " + num + " countdowns")
    }
    for (run ← 1 to 3) {
      flood(10000)
      await(dispatcher.stops.get == run)(withinMs = 10000)
      assertDispatcher(dispatcher)(starts = run, stops = run)
    }
  }

  @Test
  def dispatcherShouldCompleteAllUncompletedSenderFuturesOnDeregister {
    implicit val dispatcher = newInterceptedDispatcher
    val a = newTestActor.asInstanceOf[LocalActorRef]
    a.suspend
    val f1: Future[String] = a ? Reply("foo") mapTo manifest[String]
    val stopped = a ? PoisonPill
    val shouldBeCompleted = for (i ← 1 to 10) yield a ? Reply(i)
    a.resume
    assert(f1.get === "foo")
    stopped.await
    for (each ← shouldBeCompleted)
      assert(each.exception.get.isInstanceOf[ActorKilledException])
    a.stop()
  }

  @Test
  def dispatcherShouldContinueToProcessMessagesWhenAThreadGetsInterrupted {
    filterEvents(EventFilter[InterruptedException]("Ping!"), EventFilter[akka.event.EventHandler.EventHandlerException]) {
      implicit val dispatcher = newInterceptedDispatcher
      val a = newTestActor
      val f1 = a ? Reply("foo")
      val f2 = a ? Reply("bar")
      val f3 = a ? Interrupt
      val f4 = a ? Reply("foo2")
      val f5 = a ? Interrupt
      val f6 = a ? Reply("bar2")

      assert(f1.get === "foo")
      assert(f2.get === "bar")
      assert((intercept[InterruptedException] {
        f3.get
      }).getMessage === "Ping!")
      assert(f4.get === "foo2")
      assert((intercept[InterruptedException] {
        f5.get
      }).getMessage === "Ping!")
      assert(f6.get === "bar2")
    }
  }

  @Test
  def dispatcherShouldContinueToProcessMessagesWhenExceptionIsThrown {
    filterEvents(EventFilter[IndexOutOfBoundsException], EventFilter[RemoteException]) {
      implicit val dispatcher = newInterceptedDispatcher
      val a = newTestActor
      val f1 = a ? Reply("foo")
      val f2 = a ? Reply("bar")
      val f3 = a ? new ThrowException(new IndexOutOfBoundsException("IndexOutOfBoundsException"))
      val f4 = a ? Reply("foo2")
      val f5 = a ? new ThrowException(new RemoteException("RemoteException"))
      val f6 = a ? Reply("bar2")

      assert(f1.get === "foo")
      assert(f2.get === "bar")
      assert((intercept[IndexOutOfBoundsException] {
        f3.get
      }).getMessage === "IndexOutOfBoundsException")
      assert(f4.get === "foo2")
      assert((intercept[RemoteException] {
        f5.get
      }).getMessage === "RemoteException")
      assert(f6.get === "bar2")
    }
  }
}

class DispatcherModelTest extends ActorModelSpec {
  def newInterceptedDispatcher =
    new Dispatcher("foo") with MessageDispatcherInterceptor
}

class BalancingDispatcherModelTest extends ActorModelSpec {
  def newInterceptedDispatcher =
    new BalancingDispatcher("foo") with MessageDispatcherInterceptor
}

