/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.scaladsl

//#manual-scheduling-simple
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

import scala.concurrent.duration._

class ManualTimerExampleSpec extends AbstractActorSpec {

  override def config = ManualTime.config

  val manualTime = ManualTime()

  "A timer" must {
    "schedule non-repeated ticks" in {
      case object Tick
      case object Tock

      val probe = TestProbe[Tock.type]()
      val behavior = Behaviors.withTimers[Tick.type] { timer ⇒
        timer.startSingleTimer("T", Tick, 10.millis)
        Behaviors.receive { (ctx, Tick) ⇒
          probe.ref ! Tock
          Behaviors.same
        }
      }

      spawn(behavior)

      manualTime.expectNoMessageFor(9.millis, probe)

      manualTime.timePasses(2.millis)
      probe.expectMessage(Tock)

      manualTime.expectNoMessageFor(10.seconds, probe)
    }
    //#manual-scheduling-simple

    "schedule repeated ticks" in {
      case object Tick
      case object Tock

      val probe = TestProbe[Tock.type]()
      val behavior = Behaviors.withTimers[Tick.type] { timer ⇒
        timer.startPeriodicTimer("T", Tick, 10.millis)
        Behaviors.receive { (ctx, Tick) ⇒
          probe.ref ! Tock
          Behaviors.same
        }
      }

      spawn(behavior)

      for (_ ← Range(0, 5)) {
        manualTime.expectNoMessageFor(9.millis, probe)

        manualTime.timePasses(1.milli)
        probe.expectMessage(Tock)
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
        Behaviors.receive { (ctx, cmd) ⇒
          cmd match {
            case Tick(n) ⇒
              probe.ref ! Tock(n)
              Behaviors.same
            case SlowThenBump(nextCount) ⇒
              manualTime.timePasses(interval)
              timer.startPeriodicTimer("T", Tick(nextCount), interval)
              Behaviors.same
          }
        }
      }

      val ref = spawn(behavior)

      manualTime.timePasses(11.millis)
      probe.expectMessage(Tock(1))

      // next Tock(1) enqueued in mailboxed, but should be discarded because of new timer
      ref ! SlowThenBump(2)
      manualTime.expectNoMessageFor(interval, probe)

      manualTime.timePasses(interval)
      probe.expectMessage(Tock(2))
    }

    //#manual-scheduling-simple
  }
}
//#manual-scheduling-simple
