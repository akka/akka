/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.Behavior
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.internal.InternalProtocol.IncomingCommand
import akka.persistence.typed.internal.InternalProtocol.RecoveryPermitGranted
import org.scalatest.WordSpecLike

class StashStateSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "StashState" should {

    "clear buffer on PostStop" in {
      val probe = TestProbe[Int]()
      val behavior = TestBehavior(probe)
      val ref = spawn(behavior)
      ref ! RecoveryPermitGranted
      ref ! RecoveryPermitGranted
      probe.expectMessage(1)
      probe.expectMessage(2)
      ref ! IncomingCommand("bye")
      probe.expectTerminated(ref, 100.millis)

      spawn(behavior) ! RecoveryPermitGranted
      probe.expectMessage(1)
    }
  }

  object TestBehavior {

    def apply(probe: TestProbe[Int]): Behavior[InternalProtocol] = {
      val settings = dummySettings()
      Behaviors.setup[InternalProtocol] { _ ⇒
        val stashState = new StashState(settings)
        Behaviors.receiveMessagePartial[InternalProtocol] {
          case RecoveryPermitGranted ⇒
            stashState.internalStashBuffer.stash(RecoveryPermitGranted)
            probe.ref ! stashState.internalStashBuffer.size
            Behaviors.same[InternalProtocol]
          case _: IncomingCommand[_] ⇒ Behaviors.stopped
        }.receiveSignal {
          case (_, _) ⇒
            stashState.clearStashBuffers()
            Behaviors.stopped[InternalProtocol]
        }
      }
    }
  }

  private def dummySettings(capacity: Int = 42) =
    EventSourcedSettings(
      stashCapacity = capacity,
      stashOverflowStrategyConfigurator = "akka.persistence.ThrowExceptionConfigurator",
      logOnStashing = false,
      recoveryEventTimeout = 3.seconds,
      journalPluginId = "",
      snapshotPluginId = "")

}
