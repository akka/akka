package akka.actor.typed

//#manual-scheduling-simple
import scala.concurrent.duration._

import akka.actor.typed.scaladsl.Behaviors

import org.scalatest.WordSpecLike

import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.{ ManualTime, TestProbe }

class ManualTimerSpec extends TestKit(ManualTime.config) with ManualTime with WordSpecLike {

  "A timer" must {
    "schedule non-repeated ticks" in {
      case object Tick
      case object Tock

      val probe = TestProbe[Tock.type]()
      val behavior = Behaviors.withTimers[Tick.type] { timer ⇒
        timer.startSingleTimer("T", Tick, 10.millis)
        Behaviors.immutable { (ctx, Tick) ⇒
          probe.ref ! Tock
          Behaviors.same
        }
      }

      spawn(behavior)

      scheduler.expectNoMessageFor(9.millis, probe)

      scheduler.timePasses(2.millis)
      probe.expectMsg(Tock)

      scheduler.expectNoMessageFor(10.seconds, probe)
    }
    //#manual-scheduling-simple

    "schedule repeated ticks" in {
      case object Tick
      case object Tock

      val probe = TestProbe[Tock.type]()
      val behavior = Behaviors.withTimers[Tick.type] { timer ⇒
        timer.startPeriodicTimer("T", Tick, 10.millis)
        Behaviors.immutable { (ctx, Tick) ⇒
          probe.ref ! Tock
          Behaviors.same
        }
      }

      spawn(behavior)

      for (_ ← Range(0, 5)) {
        scheduler.expectNoMessageFor(9.millis, probe)

        scheduler.timePasses(1.milli)
        probe.expectMsg(Tock)
      }
    }

    "replace timer" in {
      sealed trait Command
      case class Tick(n: Int) extends Command
      case class SlowThenBump(nextCount: Int) extends Command
      sealed trait Event
      case class Tock(n: Int) extends Event

      val probe = TestProbe[Event]("evt")
      val interval = 10.millis

      val behavior = Behaviors.withTimers[Command] { timer ⇒
        timer.startPeriodicTimer("T", Tick(1), interval)
        Behaviors.immutable { (ctx, cmd) ⇒
          cmd match {
            case Tick(n) ⇒
              probe.ref ! Tock(n)
              Behaviors.same
            case SlowThenBump(nextCount) ⇒
              scheduler.timePasses(interval)
              timer.startPeriodicTimer("T", Tick(nextCount), interval)
              Behaviors.same
          }
        }
      }

      val ref = spawn(behavior)

      scheduler.timePasses(11.millis)
      probe.expectMsg(Tock(1))

      // next Tock(1) enqueued in mailboxed, but should be discarded because of new timer
      ref ! SlowThenBump(2)
      scheduler.expectNoMessageFor(interval, probe)

      scheduler.timePasses(interval)
      probe.expectMsg(Tock(2))
    }

    //#manual-scheduling-simple
  }
}
//#manual-scheduling-simple
