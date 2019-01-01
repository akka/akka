/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, Signal }
import akka.actor.testkit.typed.scaladsl.TestProbe

import scala.concurrent.duration._
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class StashReferenceManagementTest extends ScalaTestWithActorTestKit with WordSpecLike {
  import InternalProtocol._

  case class Impl() extends StashReferenceManagement

  "EventsourcedStashReferenceManagement instance" should {
    "initialize stash only once" in {
      val ref = Impl()
      assert(ref.stashBuffer(dummySettings()).eq(ref.stashBuffer(dummySettings())))
    }
    // or should we?
    "not reinitialize when capacity changes" in {
      val ref = Impl()
      assert(ref.stashBuffer(dummySettings()).eq(ref.stashBuffer(dummySettings(21))))
    }
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

  object TestBehavior extends StashReferenceManagement {

    def apply(probe: TestProbe[Int]): Behavior[InternalProtocol] = {
      val settings = dummySettings()
      Behaviors.setup[InternalProtocol](ctx ⇒
        Behaviors.receiveMessagePartial[InternalProtocol] {
          case RecoveryPermitGranted ⇒
            stashBuffer(settings).stash(RecoveryPermitGranted)
            probe.ref ! stashBuffer(settings).size
            Behaviors.same[InternalProtocol]
          case _: IncomingCommand[_] ⇒ Behaviors.stopped
        }.receiveSignal {
          case (_, signal: Signal) ⇒
            clearStashBuffer()
            Behaviors.stopped[InternalProtocol]
        }
      )
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
