/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl._
import org.scalatest.WordSpecLike

class TimerSpec extends TestKit("TimerSpec")
  with WordSpecLike {

  sealed trait Command
  case class Tick(n: Int) extends Command
  case object Bump extends Command
  case class SlowThenBump(latch: CountDownLatch) extends Command
  case object End extends Command
  case class Throw(e: Throwable) extends Command
  case object Cancel extends Command
  case class SlowThenThrow(latch: CountDownLatch, e: Throwable) extends Command

  sealed trait Event
  case class Tock(n: Int) extends Event
  case class GotPostStop(timerActive: Boolean) extends Event
  case class GotPreRestart(timerActive: Boolean) extends Event

  class Exc extends RuntimeException("simulated exc") with NoStackTrace

  implicit val testSettings = TestKitSettings(system)

  val interval = 1.second

  def target(monitor: ActorRef[Event], timer: TimerScheduler[Command], bumpCount: Int): Behavior[Command] = {
    def bump(): Behavior[Command] = {
      val nextCount = bumpCount + 1
      timer.startPeriodicTimer("T", Tick(nextCount), interval)
      target(monitor, timer, nextCount)
    }

    Behaviors.immutable[Command] { (ctx, cmd) ⇒
      cmd match {
        case Tick(n) ⇒
          monitor ! Tock(n)
          Behaviors.same
        case Bump ⇒
          bump()
        case SlowThenBump(latch) ⇒
          latch.await(10, TimeUnit.SECONDS)
          bump()
        case End ⇒
          Behaviors.stopped
        case Cancel ⇒
          timer.cancel("T")
          Behaviors.same
        case Throw(e) ⇒
          throw e
        case SlowThenThrow(latch, e) ⇒
          latch.await(10, TimeUnit.SECONDS)
          throw e
      }
    } onSignal {
      case (ctx, PreRestart) ⇒
        monitor ! GotPreRestart(timer.isTimerActive("T"))
        Behaviors.same
      case (ctx, PostStop) ⇒
        monitor ! GotPostStop(timer.isTimerActive("T"))
        Behaviors.same
    }
  }

  "A timer" must {
    "schedule non-repeated ticks" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer ⇒
        timer.startSingleTimer("T", Tick(1), 10.millis)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.expectMsg(Tock(1))
      probe.expectNoMessage()

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "schedule repeated ticks" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.within((interval * 4) - 100.millis) {
        probe.expectMsg(Tock(1))
        probe.expectMsg(Tock(1))
        probe.expectMsg(Tock(1))
      }

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "replace timer" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.expectMsg(Tock(1))
      val latch = new CountDownLatch(1)
      // next Tock(1) enqueued in mailboxed, but should be discarded because of new timer
      ref ! SlowThenBump(latch)
      probe.expectNoMessage(interval + 100.millis.dilated)
      latch.countDown()
      probe.expectMsg(Tock(2))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "cancel timer" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.expectMsg(Tock(1))
      ref ! Cancel
      probe.expectNoMessage(interval + 100.millis.dilated)

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "discard timers from old incarnation after restart, alt 1" in {
      val probe = TestProbe[Event]("evt")
      val startCounter = new AtomicInteger(0)
      val behv = Behaviors.supervise(Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(startCounter.incrementAndGet()), interval)
        target(probe.ref, timer, 1)
      }).onFailure[Exception](SupervisorStrategy.restart)

      val ref = spawn(behv)
      probe.expectMsg(Tock(1))

      val latch = new CountDownLatch(1)
      // next Tock(1) is enqueued in mailbox, but should be discarded by new incarnation
      ref ! SlowThenThrow(latch, new Exc)
      probe.expectNoMessage(interval + 100.millis.dilated)
      latch.countDown()
      probe.expectMsg(GotPreRestart(false))
      probe.expectNoMessage(interval / 2)
      probe.expectMsg(Tock(2))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "discard timers from old incarnation after restart, alt 2" in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.supervise(Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }).onFailure[Exception](SupervisorStrategy.restart)

      val ref = spawn(behv)
      probe.expectMsg(Tock(1))
      // change state so that we see that the restart starts over again
      ref ! Bump

      probe.expectMsg(Tock(2))

      val latch = new CountDownLatch(1)
      // next Tock(2) is enqueued in mailbox, but should be discarded by new incarnation
      ref ! SlowThenThrow(latch, new Exc)
      probe.expectNoMessage(interval + 100.millis.dilated)
      latch.countDown()
      probe.expectMsg(GotPreRestart(false))
      probe.expectMsg(Tock(1))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    "cancel timers when stopped from exception" in {
      val probe = TestProbe[Event]()
      val behv = Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }
      val ref = spawn(behv)
      ref ! Throw(new Exc)
      probe.expectMsg(GotPostStop(false))
    }

    "cancel timers when stopped voluntarily" in {
      val probe = TestProbe[Event]()
      val behv = Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }
      val ref = spawn(behv)
      ref ! End
      probe.expectMsg(GotPostStop(false))
    }
  }
}
