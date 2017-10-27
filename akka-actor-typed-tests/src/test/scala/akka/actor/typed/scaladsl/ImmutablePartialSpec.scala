/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package scaladsl

import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

class ImmutablePartialSpec extends TestKit with TypedAkkaSpecWithShutdown {

  "An immutable partial" must {

    "correctly install the message handler" in {
      val probe = TestProbe[Command]("probe")
      val behavior =
        Behaviors.immutablePartial[Command] {
          case (_, Command2) ⇒
            probe.ref ! Command2
            Behaviors.same
        }
      val actor = spawn(behavior)

      actor ! Command1
      probe.expectNoMessage()

      actor ! Command2
      probe.expectMsg(Command2)
    }
  }

  private sealed trait Command
  private case object Command1 extends Command
  private case object Command2 extends Command
}
