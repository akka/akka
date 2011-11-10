/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import org.scalatest.Assertions._
import akka.testkit.{ filterEvents, EventFilter, AkkaSpec }
import akka.dispatch._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import akka.util.Switch
import java.rmi.RemoteException
import org.junit.{ After, Test }
import akka.actor._
import util.control.NoStackTrace
import akka.AkkaApplication
import akka.util.duration._
import akka.event.Logging.Error

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
      case Reply(msg)                   ⇒ ack; sender ! msg; busy.switchOff()
      case TryReply(msg)                ⇒ ack; sender.tell(msg); busy.switchOff()
      case Forward(to, msg)             ⇒ ack; to.forward(msg); busy.switchOff()
      case CountDown(latch)             ⇒ ack; latch.countDown(); busy.switchOff()
      case Increment(count)             ⇒ ack; count.incrementAndGet(); busy.switchOff()
      case CountDownNStop(l)            ⇒ ack; l.countDown(); self.stop(); busy.switchOff()
      case Restart                      ⇒ ack; busy.switchOff(); throw new Exception("Restart requested")
      case Interrupt                    ⇒ ack; sender ! Status.Failure(new ActorInterruptedException(new InterruptedException("Ping!"))); busy.switchOff(); throw new InterruptedException("Ping!")
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
    override def toString = "InterceptorStats(susp=" + suspensions +
      ",res=" + resumes + ",reg=" + registers + ",unreg=" + unregisters +
      ",recv=" + msgsReceived + ",proc=" + msgsProcessed + ",restart=" + restarts
  }

  trait MessageDispatcherInterceptor extends MessageDispatcher {
    val stats = new ConcurrentHashMap[ActorRef, InterceptorStats]
    val starts = new AtomicLong(0)
    val stops = new AtomicLong(0)

    def getStats(actorRef: ActorRef) = {
      stats.putIfAbsent(actorRef, new InterceptorStats) match {
        case null  ⇒ stats.get(actorRef)
        case other ⇒ other
      }
    }

    abstract override def suspend(actor: ActorCell) {
      getStats(actor.self).suspensions.incrementAndGet()
      super.suspend(actor)
    }

    abstract override def resume(actor: ActorCell) {
      super.resume(actor)
      getStats(actor.self).resumes.incrementAndGet()
    }

    protected[akka] abstract override def register(actor: ActorCell) {
      getStats(actor.self).registers.incrementAndGet()
      super.register(actor)
    }

    protected[akka] abstract override def unregister(actor: ActorCell) {
      getStats(actor.self).unregisters.incrementAndGet()
      super.unregister(actor)
    }

    protected[akka] abstract override def dispatch(receiver: ActorCell, invocation: Envelope) {
      val stats = getStats(receiver.self)
      stats.msgsReceived.incrementAndGet()
      super.dispatch(receiver, invocation)
    }

    protected[akka] abstract override def start() {
      starts.incrementAndGet()
      super.start()
    }

    protected[akka] abstract override def shutdown() {
      stops.incrementAndGet()
      super.shutdown()
    }
  }

  def assertDispatcher(dispatcher: MessageDispatcherInterceptor)(
    starts: Long = dispatcher.starts.get(),
    stops: Long = dispatcher.stops.get())(implicit app: AkkaApplication) {
    val deadline = System.currentTimeMillis + dispatcher.timeoutMs * 5
    try {
      await(deadline)(starts == dispatcher.starts.get)
      await(deadline)(stops == dispatcher.stops.get)
    } catch {
      case e ⇒
        app.mainbus.publish(Error(e, dispatcher, "actual: starts=" + dispatcher.starts.get + ",stops=" + dispatcher.stops.get +
          " required: starts=" + starts + ",stops=" + stops))
        throw e
    }
  }

  def assertCountDown(latch: CountDownLatch, wait: Long, hint: String) {
    if (!latch.await(wait, TimeUnit.MILLISECONDS))
      fail("Failed to count down within " + wait + " millis (count at " + latch.getCount + "). " + hint)
  }

  def assertNoCountDown(latch: CountDownLatch, wait: Long, hint: String) {
    if (latch.await(wait, TimeUnit.MILLISECONDS))
      fail("Expected count down to fail after " + wait + " millis. " + hint)
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
    restarts: Long = 0)(implicit app: AkkaApplication) {
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
    restarts: Long = statsFor(actorRef).restarts.get())(implicit app: AkkaApplication) {
    val stats = statsFor(actorRef, Option(dispatcher).getOrElse(actorRef.asInstanceOf[LocalActorRef].underlying.dispatcher))
    val deadline = System.currentTimeMillis + 1000
    try {
      await(deadline)(stats.suspensions.get() == suspensions)
      await(deadline)(stats.resumes.get() == resumes)
      await(deadline)(stats.registers.get() == registers)
      await(deadline)(stats.unregisters.get() == unregisters)
      await(deadline)(stats.msgsReceived.get() == msgsReceived)
      await(deadline)(stats.msgsProcessed.get() == msgsProcessed)
      await(deadline)(stats.restarts.get() == restarts)
    } catch {
      case e ⇒
        app.mainbus.publish(Error(e, dispatcher, "actual: " + stats + ", required: InterceptorStats(susp=" + suspensions +
          ",res=" + resumes + ",reg=" + registers + ",unreg=" + unregisters +
          ",recv=" + msgsReceived + ",proc=" + msgsProcessed + ",restart=" + restarts))
        throw e
    }
  }

  def await(until: Long)(condition: ⇒ Boolean): Unit = try {
    while (System.currentTimeMillis() <= until) {
      try {
        if (condition) return else Thread.sleep(25)
      } catch {
        case e: InterruptedException ⇒
      }
    }
    throw new AssertionError("await failed")
  }
}

abstract class ActorModelSpec extends AkkaSpec {

  import ActorModelSpec._

  def newTestActor(dispatcher: MessageDispatcher) = app.actorOf(Props[DispatcherActor].withDispatcher(dispatcher))

  protected def newInterceptedDispatcher: MessageDispatcherInterceptor
  protected def dispatcherType: String

  // BalancingDispatcher of course does not work when another actor is in the pool, so overridden below
  protected def wavesSupervisorDispatcher(dispatcher: MessageDispatcher) = dispatcher

  "A " + dispatcherType must {

    "must dynamically handle its own life cycle" in {
      implicit val dispatcher = newInterceptedDispatcher
      assertDispatcher(dispatcher)(starts = 0, stops = 0)
      val a = newTestActor(dispatcher)
      assertDispatcher(dispatcher)(starts = 1, stops = 0)
      a.stop()
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
      assertDispatcher(dispatcher)(starts = 2, stops = 2)

      val a2 = newTestActor(dispatcher)
      val futures2 = for (i ← 1 to 10) yield Future { i }

      assertDispatcher(dispatcher)(starts = 3, stops = 2)

      a2.stop
      assertDispatcher(dispatcher)(starts = 3, stops = 3)
    }

    "process messages one at a time" in {
      implicit val dispatcher = newInterceptedDispatcher
      val start, oneAtATime = new CountDownLatch(1)
      val a = newTestActor(dispatcher)

      a ! CountDown(start)
      assertCountDown(start, 3.seconds.dilated.toMillis, "Should process first message within 3 seconds")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, msgsProcessed = 1)

      a ! Wait(1000)
      a ! CountDown(oneAtATime)
      // in case of serialization violation, restart would happen instead of count down
      assertCountDown(oneAtATime, (1.5 seconds).dilated.toMillis, "Processed message when allowed")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 3, msgsProcessed = 3)

      a.stop()
      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 3, msgsProcessed = 3)
    }

    "handle queueing from multiple threads" in {
      implicit val dispatcher = newInterceptedDispatcher
      val counter = new CountDownLatch(200)
      val a = newTestActor(dispatcher)

      for (i ← 1 to 10) {
        spawn {
          for (i ← 1 to 20) {
            a ! WaitAck(1, counter)
          }
        }
      }
      assertCountDown(counter, 3.seconds.dilated.toMillis, "Should process 200 messages")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 200, msgsProcessed = 200)

      a.stop()
    }

    def spawn(f: ⇒ Unit) {
      val thread = new Thread {
        override def run {
          try {
            f
          } catch {
            case e ⇒ app.mainbus.publish(Error(e, this, "error in spawned thread"))
          }
        }
      }
      thread.start()
    }

    "not process messages for a suspended actor" in {
      implicit val dispatcher = newInterceptedDispatcher
      val a = newTestActor(dispatcher).asInstanceOf[LocalActorRef]
      val done = new CountDownLatch(1)
      a.suspend
      a ! CountDown(done)
      assertNoCountDown(done, 1000, "Should not process messages while suspended")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, suspensions = 1)

      a.resume
      assertCountDown(done, 3.seconds.dilated.toMillis, "Should resume processing of messages when resumed")
      assertRefDefaultZero(a)(registers = 1, msgsReceived = 1, msgsProcessed = 1,
        suspensions = 1, resumes = 1)

      a.stop()
      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1,
        suspensions = 1, resumes = 1)
    }

    "handle waves of actors" in {
      val dispatcher = newInterceptedDispatcher
      val props = Props[DispatcherActor].withDispatcher(dispatcher)

      def flood(num: Int) {
        val cachedMessage = CountDownNStop(new CountDownLatch(num))
        val stopLatch = new CountDownLatch(num)
        val waitTime = (30 seconds).dilated.toMillis
        val boss = actorOf(Props(context ⇒ {
          case "run" ⇒
            for (_ ← 1 to num) {
              val child = context.actorOf(props)
              context.self startsMonitoring child
              child ! cachedMessage
            }
          case Terminated(child) ⇒
            stopLatch.countDown()
        }).withDispatcher(wavesSupervisorDispatcher(dispatcher)))
        boss ! "run"
        assertCountDown(cachedMessage.latch, waitTime, "Counting down from " + num)
        assertCountDown(stopLatch, waitTime, "Expected all children to stop")
        boss.stop()
      }
      for (run ← 1 to 3) {
        flood(50000)
        assertDispatcher(dispatcher)(starts = run, stops = run)
      }
    }

    "continue to process messages when a thread gets interrupted" in {
      filterEvents(EventFilter[InterruptedException](), EventFilter[akka.event.Logging.EventHandlerException]()) {
        implicit val dispatcher = newInterceptedDispatcher
        implicit val timeout = Timeout(5 seconds)
        val a = newTestActor(dispatcher)
        val f1 = a ? Reply("foo")
        val f2 = a ? Reply("bar")
        val f3 = try { a ? Interrupt } catch { case ie: InterruptedException ⇒ new KeptPromise(Left(ActorInterruptedException(ie))) }
        val f4 = a ? Reply("foo2")
        val f5 = try { a ? Interrupt } catch { case ie: InterruptedException ⇒ new KeptPromise(Left(ActorInterruptedException(ie))) }
        val f6 = a ? Reply("bar2")

        assert(f1.get === "foo")
        assert(f2.get === "bar")
        assert(f4.get === "foo2")
        assert(intercept[ActorInterruptedException](f3.get).getMessage === "Ping!")
        assert(f6.get === "bar2")
        assert(intercept[ActorInterruptedException](f5.get).getMessage === "Ping!")
      }
    }

    "continue to process messages when exception is thrown" in {
      filterEvents(EventFilter[IndexOutOfBoundsException](), EventFilter[RemoteException]()) {
        implicit val dispatcher = newInterceptedDispatcher
        val a = newTestActor(dispatcher)
        val f1 = a ? Reply("foo")
        val f2 = a ? Reply("bar")
        val f3 = a ? ThrowException(new IndexOutOfBoundsException("IndexOutOfBoundsException"))
        val f4 = a ? Reply("foo2")
        val f5 = a ? ThrowException(new RemoteException("RemoteException"))
        val f6 = a ? Reply("bar2")

        assert(f1.get === "foo")
        assert(f2.get === "bar")
        assert(f4.get === "foo2")
        assert(f6.get === "bar2")
        assert(f3.result === None)
        assert(f5.result === None)
      }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatcherModelSpec extends ActorModelSpec {
  import ActorModelSpec._

  def newInterceptedDispatcher = ThreadPoolConfigDispatcherBuilder(config ⇒
    new Dispatcher(app, "foo", app.AkkaConfig.DispatcherThroughput,
      app.dispatcherFactory.ThroughputDeadlineTimeMillis, app.dispatcherFactory.MailboxType,
      config, app.dispatcherFactory.DispatcherShutdownMillis) with MessageDispatcherInterceptor,
    ThreadPoolConfig(app)).build.asInstanceOf[MessageDispatcherInterceptor]

  def dispatcherType = "Dispatcher"

  "A " + dispatcherType must {
    "process messages in parallel" in {
      implicit val dispatcher = newInterceptedDispatcher
      val aStart, aStop, bParallel = new CountDownLatch(1)
      val a, b = newTestActor(dispatcher)

      a ! Meet(aStart, aStop)
      assertCountDown(aStart, 3.seconds.dilated.toMillis, "Should process first message within 3 seconds")

      b ! CountDown(bParallel)
      assertCountDown(bParallel, 3.seconds.dilated.toMillis, "Should process other actors in parallel")

      aStop.countDown()

      a.stop
      b.stop

      while (!a.isShutdown && !b.isShutdown) {} //Busy wait for termination

      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
      assertRefDefaultZero(b)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BalancingDispatcherModelSpec extends ActorModelSpec {
  import ActorModelSpec._

  def newInterceptedDispatcher = ThreadPoolConfigDispatcherBuilder(config ⇒
    new BalancingDispatcher(app, "foo", 1, // TODO check why 1 here? (came from old test)
      app.dispatcherFactory.ThroughputDeadlineTimeMillis, app.dispatcherFactory.MailboxType,
      config, app.dispatcherFactory.DispatcherShutdownMillis) with MessageDispatcherInterceptor,
    ThreadPoolConfig(app)).build.asInstanceOf[MessageDispatcherInterceptor]

  def dispatcherType = "Balancing Dispatcher"

  override def wavesSupervisorDispatcher(dispatcher: MessageDispatcher) = app.dispatcher

  "A " + dispatcherType must {
    "process messages in parallel" in {
      implicit val dispatcher = newInterceptedDispatcher
      val aStart, aStop, bParallel = new CountDownLatch(1)
      val a, b = newTestActor(dispatcher)

      a ! Meet(aStart, aStop)
      assertCountDown(aStart, 3.seconds.dilated.toMillis, "Should process first message within 3 seconds")

      b ! CountDown(bParallel)
      assertCountDown(bParallel, 3.seconds.dilated.toMillis, "Should process other actors in parallel")

      aStop.countDown()

      a.stop
      b.stop

      while (!a.isShutdown && !b.isShutdown) {} //Busy wait for termination

      assertRefDefaultZero(a)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
      assertRefDefaultZero(b)(registers = 1, unregisters = 1, msgsReceived = 1, msgsProcessed = 1)
    }
  }
}
