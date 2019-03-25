/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler
import akka.testkit.{ EventFilter, TimingTest }
import org.scalatest.WordSpecLike

class TimerSpec extends ScalaTestWithActorTestKit("""
    akka.loggers = [ akka.testkit.TestEventListener ]
  """) with WordSpecLike {

  // FIXME #24348: eventfilter support in typed testkit
  import scaladsl.adapter._
  implicit val untypedSystem = system.toUntyped

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
  case object Cancelled extends Event

  class Exc extends RuntimeException("simulated exc") with NoStackTrace

  val interval = 1.second

  def target(monitor: ActorRef[Event], timer: TimerScheduler[Command], bumpCount: Int): Behavior[Command] = {
    def bump(): Behavior[Command] = {
      val nextCount = bumpCount + 1
      timer.startPeriodicTimer("T", Tick(nextCount), interval)
      target(monitor, timer, nextCount)
    }

    Behaviors
      .receive[Command] { (context, cmd) =>
        cmd match {
          case Tick(n) =>
            monitor ! Tock(n)
            Behaviors.same
          case Bump =>
            bump()
          case SlowThenBump(latch) =>
            latch.await(10, TimeUnit.SECONDS)
            bump()
          case End =>
            Behaviors.stopped
          case Cancel =>
            timer.cancel("T")
            monitor ! Cancelled
            Behaviors.same
          case Throw(e) =>
            throw e
          case SlowThenThrow(latch, e) =>
            latch.await(10, TimeUnit.SECONDS)
            throw e
        }
      }
      .receiveSignal {
        case (_, PreRestart) =>
          monitor ! GotPreRestart(timer.isTimerActive("T"))
          Behaviors.same
        case (_, PostStop) =>
          monitor ! GotPostStop(timer.isTimerActive("T"))
          Behaviors.same
      }
  }

  "A timer" must {
    "schedule non-repeated ticks" taggedAs TimingTest in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer =>
        timer.startSingleTimer("T", Tick(1), 10.millis)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.expectMessage(Tock(1))
      probe.expectNoMessage()

      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "schedule repeated ticks" taggedAs TimingTest in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer =>
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.within((interval * 4) - 100.millis) {
        probe.expectMessage(Tock(1))
        probe.expectMessage(Tock(1))
        probe.expectMessage(Tock(1))
      }

      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "replace timer" taggedAs TimingTest in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer =>
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.expectMessage(Tock(1))
      val latch = new CountDownLatch(1)
      // next Tock(1) enqueued in mailboxed, but should be discarded because of new timer
      ref ! SlowThenBump(latch)
      probe.expectNoMessage(interval + 100.millis.dilated)
      latch.countDown()
      probe.expectMessage(Tock(2))

      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "cancel timer" taggedAs TimingTest in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors.withTimers[Command] { timer =>
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }

      val ref = spawn(behv)
      probe.expectMessage(Tock(1))
      ref ! Cancel
      probe.fishForMessage(3.seconds) {
        // we don't know that we will see exactly one tock
        case _: Tock => FishingOutcomes.continue
        // but we know that after we saw Cancelled we won't see any more
        case Cancelled => FishingOutcomes.complete
        case message   => FishingOutcomes.fail(s"unexpected message: $message")
      }
      probe.expectNoMessage(interval + 100.millis.dilated)

      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "discard timers from old incarnation after restart, alt 1" taggedAs TimingTest in {
      val probe = TestProbe[Event]("evt")
      val startCounter = new AtomicInteger(0)
      val behv = Behaviors
        .supervise(Behaviors.withTimers[Command] { timer =>
          timer.startPeriodicTimer("T", Tick(startCounter.incrementAndGet()), interval)
          target(probe.ref, timer, 1)
        })
        .onFailure[Exception](SupervisorStrategy.restart)

      val ref = spawn(behv)
      probe.expectMessage(Tock(1))

      val latch = new CountDownLatch(1)
      EventFilter[Exc](occurrences = 1).intercept {
        // next Tock(1) is enqueued in mailbox, but should be discarded by new incarnation
        ref ! SlowThenThrow(latch, new Exc)

        probe.expectNoMessage(interval + 100.millis.dilated)
        latch.countDown()
        probe.expectMessage(GotPreRestart(false))
        probe.expectNoMessage(interval / 2)
        probe.expectMessage(Tock(2))
      }
      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "discard timers from old incarnation after restart, alt 2" taggedAs TimingTest in {
      val probe = TestProbe[Event]("evt")
      val behv = Behaviors
        .supervise(Behaviors.withTimers[Command] { timer =>
          timer.startPeriodicTimer("T", Tick(1), interval)
          target(probe.ref, timer, 1)
        })
        .onFailure[Exception](SupervisorStrategy.restart)

      val ref = spawn(behv)
      probe.expectMessage(Tock(1))
      // change state so that we see that the restart starts over again
      ref ! Bump

      probe.expectMessage(Tock(2))

      EventFilter[Exc](occurrences = 1).intercept {
        val latch = new CountDownLatch(1)
        // next Tock(2) is enqueued in mailbox, but should be discarded by new incarnation
        ref ! SlowThenThrow(latch, new Exc)
        probe.expectNoMessage(interval + 100.millis.dilated)
        latch.countDown()
        probe.expectMessage(GotPreRestart(false))
        probe.expectMessage(Tock(1))
      }

      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "cancel timers when stopped from exception" taggedAs TimingTest in {
      val probe = TestProbe[Event]()
      val behv = Behaviors.withTimers[Command] { timer =>
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }
      val ref = spawn(behv)
      EventFilter[Exc](occurrences = 1).intercept {
        ref ! Throw(new Exc)
        probe.expectMessage(GotPostStop(false))
      }
    }

    "cancel timers when stopped voluntarily" taggedAs TimingTest in {
      val probe = TestProbe[Event]()
      val behv = Behaviors.withTimers[Command] { timer =>
        timer.startPeriodicTimer("T", Tick(1), interval)
        target(probe.ref, timer, 1)
      }
      val ref = spawn(behv)
      ref ! End
      probe.expectMessage(GotPostStop(false))
    }

    "allow for nested timers" in {
      val probe = TestProbe[String]()
      val ref = spawn(Behaviors.withTimers[String] { outerTimer =>
        outerTimer.startPeriodicTimer("outer-key", "outer-message", 50.millis)
        Behaviors.withTimers { innerTimer =>
          innerTimer.startPeriodicTimer("inner-key", "inner-message", 50.millis)
          Behaviors.receiveMessage { message =>
            if (message == "stop") Behaviors.stopped
            else {
              probe.ref ! message
              Behaviors.same
            }
          }
        }
      })

      var seen = Set.empty[String]
      probe.fishForMessage(500.millis) {
        case message =>
          seen += message
          if (seen.size == 2) FishingOutcomes.complete
          else FishingOutcomes.continue
      }

      ref ! "stop"
    }

    "keep timers when behavior changes" in {
      val probe = TestProbe[String]()
      def newBehavior(n: Int): Behavior[String] = Behaviors.withTimers[String] { timers =>
        timers.startPeriodicTimer(s"key${n}", s"message${n}", 50.milli)
        Behaviors.receiveMessage { message =>
          if (message == "stop") Behaviors.stopped
          else {
            probe.ref ! message
            newBehavior(n + 1)
          }
        }
      }

      val ref = spawn(newBehavior(1))
      var seen = Set.empty[String]
      probe.fishForMessage(500.millis) {
        case message =>
          seen += message
          if (seen.size == 2) FishingOutcomes.complete
          else FishingOutcomes.continue
      }

      ref ! "stop"
    }

    "not grow stack when nesting withTimers" in {
      def next(n: Int, probe: ActorRef[Array[StackTraceElement]]): Behavior[String] = Behaviors.withTimers { timers =>
        timers.startSingleTimer("key", "tick", 1.millis)
        Behaviors.receiveMessage { message =>
          if (n == 20) {
            val e = new RuntimeException().fillInStackTrace()
            val trace = e.getStackTrace
            probe ! trace
            Behaviors.stopped
          } else {
            next(n + 1, probe)
          }
        }
      }

      val probe = TestProbe[Array[StackTraceElement]]()
      spawn(next(0, probe.ref))
      val elements = probe.receiveMessage()
      if (elements.count(_.getClassName == "TimerInterceptor") > 1)
        fail(s"Stack contains TimerInterceptor more than once: \n${elements.mkString("\n\t")}")
    }

    "not leak timers when PostStop is used" in {
      val probe = TestProbe[Any]()
      val ref = spawn(Behaviors.withTimers[String] { timers =>
        Behaviors.setup { _ =>
          timers.startPeriodicTimer("test", "test", 250.millis)
          Behaviors.receive { (context, message) =>
            Behaviors.stopped(() => context.log.info(s"stopping"))
          }
        }
      })
      EventFilter.info("stopping").intercept {
        ref ! "stop"
      }
      probe.expectTerminated(ref)
      system.toUntyped.eventStream.subscribe(probe.ref.toUntyped, classOf[DeadLetter])
      probe.expectNoMessage(1.second)
    }
  }
}
