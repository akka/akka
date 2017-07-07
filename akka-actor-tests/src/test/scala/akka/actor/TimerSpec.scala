/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.testkit._
import scala.concurrent.Await

object TimerSpec {
  sealed trait Command
  case class Tick(n: Int) extends Command
  case object Bump extends Command
  case class SlowThenBump(latch: TestLatch) extends Command
    with NoSerializationVerificationNeeded
  case object End extends Command
  case class Throw(e: Throwable) extends Command
  case object Cancel extends Command
  case class SlowThenThrow(latch: TestLatch, e: Throwable) extends Command
    with NoSerializationVerificationNeeded

  sealed trait Event
  case class Tock(n: Int) extends Event
  case class GotPostStop(timerActive: Boolean) extends Event
  case class GotPreRestart(timerActive: Boolean) extends Event

  class Exc extends RuntimeException("simulated exc") with NoStackTrace

  def target(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int): Props =
    Props(new Target(monitor, interval, repeat, initial))

  class Target(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int) extends Actor with Timers {
    private var bumpCount = initial()

    if (repeat)
      timers.startPeriodicTimer("T", Tick(bumpCount), interval)
    else
      timers.startSingleTimer("T", Tick(bumpCount), interval)

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      monitor ! GotPreRestart(timers.isTimerActive("T"))
      // don't call super.preRestart to avoid postStop
    }

    override def postStop(): Unit = {
      monitor ! GotPostStop(timers.isTimerActive("T"))
    }

    def bump(): Unit = {
      bumpCount += 1
      timers.startPeriodicTimer("T", Tick(bumpCount), interval)
    }

    override def receive = {
      case Tick(n) ⇒
        monitor ! Tock(n)
      case Bump ⇒
        bump()
      case SlowThenBump(latch) ⇒
        Await.ready(latch, 10.seconds)
        bump()
      case End ⇒
        context.stop(self)
      case Cancel ⇒
        timers.cancel("T")
      case Throw(e) ⇒
        throw e
      case SlowThenThrow(latch, e) ⇒
        Await.ready(latch, 10.seconds)
        throw e
    }
  }

  def fsmTarget(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int): Props =
    Props(new FsmTarget(monitor, interval, repeat, initial))

  object TheState

  class FsmTarget(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int) extends FSM[TheState.type, Int] {

    private var restarting = false

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      restarting = true
      super.preRestart(reason, message)
      monitor ! GotPreRestart(isTimerActive("T"))
    }

    override def postStop(): Unit = {
      super.postStop()
      if (!restarting)
        monitor ! GotPostStop(isTimerActive("T"))
    }

    def bump(bumpCount: Int): State = {
      setTimer("T", Tick(bumpCount + 1), interval, repeat)
      stay using (bumpCount + 1)
    }

    {
      val i = initial()
      startWith(TheState, i)
      setTimer("T", Tick(i), interval, repeat)
    }

    when(TheState) {
      case Event(Tick(n), _) ⇒
        monitor ! Tock(n)
        stay
      case Event(Bump, bumpCount) ⇒
        bump(bumpCount)
      case Event(SlowThenBump(latch), bumpCount) ⇒
        Await.ready(latch, 10.seconds)
        bump(bumpCount)
      case Event(End, _) ⇒
        stop()
      case Event(Cancel, _) ⇒
        cancelTimer("T")
        stay
      case Event(Throw(e), _) ⇒
        throw e
      case Event(SlowThenThrow(latch, e), _) ⇒
        Await.ready(latch, 10.seconds)
        throw e
    }

    initialize()
  }

}

class TimerSpec extends AbstractTimerSpec {
  override def testName: String = "Timers"
  override def target(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int = () ⇒ 1): Props =
    TimerSpec.target(monitor, interval, repeat, initial)
}

class FsmTimerSpec extends AbstractTimerSpec {
  override def testName: String = "FSM Timers"
  override def target(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int = () ⇒ 1): Props =
    TimerSpec.fsmTarget(monitor, interval, repeat, initial)
}

abstract class AbstractTimerSpec extends AkkaSpec {
  import TimerSpec._

  val interval = 1.second
  val dilatedInterval = interval.dilated

  def target(monitor: ActorRef, interval: FiniteDuration, repeat: Boolean, initial: () ⇒ Int = () ⇒ 1): Props

  def testName: String

  testName must {
    "schedule non-repeated ticks" taggedAs TimingTest in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, 10.millis, repeat = false))

      probe.expectMsg(Tock(1))
      probe.expectNoMsg(100.millis)

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "schedule repeated ticks" taggedAs TimingTest in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true))
      probe.within((interval * 4) - 100.millis) {
        probe.expectMsg(Tock(1))
        probe.expectMsg(Tock(1))
        probe.expectMsg(Tock(1))
      }

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "replace timer" taggedAs TimingTest in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true))
      probe.expectMsg(Tock(1))
      val latch = new TestLatch(1)
      // next Tock(1) enqueued in mailboxed, but should be discarded because of new timer
      ref ! SlowThenBump(latch)
      probe.expectNoMsg(interval + 100.millis)
      latch.countDown()
      probe.expectMsg(Tock(2))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "cancel timer" taggedAs TimingTest in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true))
      probe.expectMsg(Tock(1))
      ref ! Cancel
      probe.expectNoMsg(dilatedInterval + 100.millis)

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "cancel timers when restarted" taggedAs TimingTest in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true))
      ref ! Throw(new Exc)
      probe.expectMsg(GotPreRestart(false))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "discard timers from old incarnation after restart, alt 1" taggedAs TimingTest in {
      val probe = TestProbe()
      val startCounter = new AtomicInteger(0)
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true,
        initial = () ⇒ startCounter.incrementAndGet()))
      probe.expectMsg(Tock(1))

      val latch = new TestLatch(1)
      // next Tock(1) is enqueued in mailbox, but should be discarded by new incarnation
      ref ! SlowThenThrow(latch, new Exc)
      probe.expectNoMsg(interval + 100.millis)
      latch.countDown()
      probe.expectMsg(GotPreRestart(false))
      probe.expectNoMsg(interval / 2)
      probe.expectMsg(Tock(2)) // this is from the startCounter increment

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "discard timers from old incarnation after restart, alt 2" taggedAs TimingTest in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true))
      probe.expectMsg(Tock(1))
      // change state so that we see that the restart starts over again
      ref ! Bump

      probe.expectMsg(Tock(2))

      val latch = new TestLatch(1)
      // next Tock(2) is enqueued in mailbox, but should be discarded by new incarnation
      ref ! SlowThenThrow(latch, new Exc)
      probe.expectNoMsg(interval + 100.millis)
      latch.countDown()
      probe.expectMsg(GotPreRestart(false))
      probe.expectMsg(Tock(1))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "cancel timers when stopped" in {
      val probe = TestProbe()
      val ref = system.actorOf(target(probe.ref, dilatedInterval, repeat = true))
      ref ! End
      probe.expectMsg(GotPostStop(false))
    }
  }
}
