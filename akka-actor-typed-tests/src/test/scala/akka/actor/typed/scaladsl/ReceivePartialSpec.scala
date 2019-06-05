/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package scaladsl

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.WordSpecLike

class ReceivePartialSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  implicit val ec = system.executionContext

  "An immutable partial" must {
    "correctly install the receiveMessage handler" in {
      val probe = TestProbe[Command]("probe")
      val behavior =
        Behaviors.receiveMessagePartial[Command] {
          case Command2 =>
            probe.ref ! Command2
            Behaviors.same
        }
      val actor = spawn(behavior)

      actor ! Command1
      probe.expectNoMessage()

      actor ! Command2
      probe.expectMessage(Command2)
    }

    "correctly install the receive handler" in {
      val probe = TestProbe[Command]("probe")
      val behavior =
        Behaviors.receivePartial[Command] {
          case (_, Command2) =>
            probe.ref ! Command2
            Behaviors.same
        }
      val actor = spawn(behavior)

      actor ! Command1
      probe.expectNoMessage()

      actor ! Command2
      probe.expectMessage(Command2)
    }
  }

  private sealed trait Command
  private case object Command1 extends Command
  private case object Command2 extends Command
}
