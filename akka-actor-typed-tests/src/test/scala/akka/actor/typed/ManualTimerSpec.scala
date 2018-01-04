package akka.actor.typed

import scala.concurrent.duration._

import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.{ ManualTime, TestProbe }
import org.scalatest.WordSpecLike

class ManualTimerSpec extends TestKit() with ManualTime with WordSpecLike {
  //#manual-scheduling-simple
  "A timer" must {
    "schedule non-repeated ticks" in {
      case object Tick
      case object Tock

      val probe = TestProbe[Tock.type]()
      val behv = Behaviors.withTimers[Tick.type] { timer ⇒
        timer.startSingleTimer("T", Tick, 10.millis)
        Behaviors.immutable { (ctx, Tick) ⇒
          probe.ref ! Tock
          Behaviors.same
        }
      }

      val ref = spawn(behv)

      scheduler.timePasses(9.millis)
      probe.expectNoMessage(Duration.Zero)

      scheduler.timePasses(2.millis)
      probe.expectMsg(Tock)
      probe.expectNoMessage(Duration.Zero)
    }
  }
  //#manual-scheduling-simple
}
