/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.testkit.typed.{ BehaviorTestkit, TestKit, TestKitSettings }
import akka.testkit.typed.scaladsl.TestProbe

import scala.concurrent.duration.DurationInt

class ImmutablePartialSpec extends TestKit with TypedAkkaSpecWithShutdown {

  "An immutable partial" must {

    "correctly install the message handler" in {
      val probe = TestProbe[Command]("probe")
      val behavior =
        Actor.immutablePartial[Command] {
          case (_, Command2) ⇒
            probe.ref ! Command2
            Actor.same
        }
      val testkit = BehaviorTestkit(behavior)

      testkit.run(Command1)
      testkit.currentBehavior shouldBe behavior
      probe.expectNoMsg(100.milliseconds)

      testkit.run(Command2)
      testkit.currentBehavior shouldBe behavior
      probe.expectMsg(Command2)
    }
  }

  private sealed trait Command
  private case object Command1 extends Command
  private case object Command2 extends Command
}
