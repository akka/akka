/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.TimerScheduler
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TimerSpec extends TypedSpec("""
  #akka.loglevel = DEBUG
  """) {

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

  trait RealTests extends StartSupport {
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)

    val interval = 1.second
    val dilatedInterval = interval.dilated

    def target(monitor: ActorRef[Event], timer: TimerScheduler[Command], bumpCount: Int): Behavior[Command] = {
      def bump(): Behavior[Command] = {
        val nextCount = bumpCount + 1
        timer.startPeriodicTimer("T", Tick(nextCount), interval)
        target(monitor, timer, nextCount)
      }

      Actor.immutable[Command] { (ctx, cmd) ⇒
        cmd match {
          case Tick(n) ⇒
            monitor ! Tock(n)
            Actor.same
          case Bump ⇒
            bump()
          case SlowThenBump(latch) ⇒
            latch.await(10, TimeUnit.SECONDS)
            bump()
          case End ⇒
            Actor.stopped
          case Cancel ⇒
            timer.cancel("T")
            Actor.same
          case Throw(e) ⇒
            throw e
          case SlowThenThrow(latch, e) ⇒
            latch.await(10, TimeUnit.SECONDS)
            throw e
        }
      } onSignal {
        case (ctx, PreRestart) ⇒
          monitor ! GotPreRestart(timer.isTimerActive("T"))
          Actor.same
        case (ctx, PostStop) ⇒
          monitor ! GotPostStop(timer.isTimerActive("T"))
          Actor.same
      }
    }

    def `01 must schedule non-repeated ticks`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.withTimers[Command] { timer ⇒
        timer.startSingleTimer("T", Tick(1), 10.millis)
        target(probe.ref, timer, 1)
      }

      val ref = start(behv)
      probe.expectMsg(Tock(1))
      probe.expectNoMsg(100.millis)

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    def `02 must schedule repeated ticks`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = start(behv)
      probe.within((interval * 4) - 100.millis) {
        probe.expectMsg(Tock(1))
        probe.expectMsg(Tock(1))
        probe.expectMsg(Tock(1))
      }

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    def `03 must replace timer`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = start(behv)
      probe.expectMsg(Tock(1))
      val latch = new CountDownLatch(1)
      // next Tock(1) enqueued in mailboxed, but should be discarded because of new timer
      ref ! SlowThenBump(latch)
      probe.expectNoMsg(interval + 100.millis)
      latch.countDown()
      probe.expectMsg(Tock(2))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    def `04 must cancel timer`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = start(behv)
      probe.expectMsg(Tock(1))
      ref ! Cancel
      probe.expectNoMsg(dilatedInterval + 100.millis)

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    def `05 must discard timers from old incarnation after restart, alt 1`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val startCounter = new AtomicInteger(0)
      val behv = Actor.supervise(Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(startCounter.incrementAndGet()), interval)
        target(probe.ref, timer, 1)
      }).onFailure[Exception](SupervisorStrategy.restart)

      val ref = start(behv)
      probe.expectMsg(Tock(1))

      val latch = new CountDownLatch(1)
      // next Tock(1) is enqueued in mailbox, but should be discarded by new incarnation
      ref ! SlowThenThrow(latch, new Exc)
      probe.expectNoMsg(interval + 100.millis)
      latch.countDown()
      probe.expectMsg(GotPreRestart(false))
      probe.expectNoMsg(interval / 2)
      probe.expectMsg(Tock(2))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    def `06 must discard timers from old incarnation after restart, alt 2`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.supervise(Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }).onFailure[Exception](SupervisorStrategy.restart)

      val ref = start(behv)
      probe.expectMsg(Tock(1))
      // change state so that we see that the restart starts over again
      ref ! Bump

      probe.expectMsg(Tock(2))

      val latch = new CountDownLatch(1)
      // next Tock(2) is enqueued in mailbox, but should be discarded by new incarnation
      ref ! SlowThenThrow(latch, new Exc)
      probe.expectNoMsg(interval + 100.millis)
      latch.countDown()
      probe.expectMsg(GotPreRestart(false))
      probe.expectMsg(Tock(1))

      ref ! End
      probe.expectMsg(GotPostStop(false))
    }

    def `07 must cancel timers when stopped from exception`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }
      val ref = start(behv)
      ref ! Throw(new Exc)
      probe.expectMsg(GotPostStop(false))
    }

    def `08 must cancel timers when stopped voluntarily`(): Unit = {
      val probe = TestProbe[Event]("evt")
      val behv = Actor.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }
      val ref = start(behv)
      ref ! End
      probe.expectMsg(GotPostStop(false))
    }
  }

  object `A Restarter (real, native)` extends RealTests with NativeSystem
  object `A Restarter (real, adapted)` extends RealTests with AdaptedSystem

}
