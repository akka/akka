/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, Signal, TypedAkkaSpecWithShutdown }
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol.{ IncomingCommand, RecoveryPermitGranted }
import akka.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }

import scala.concurrent.duration.{ FiniteDuration, _ }

class EventsourcedStashReferenceManagementTest extends ActorTestKit with TypedAkkaSpecWithShutdown {

  case class Impl() extends EventsourcedStashReferenceManagement

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

  object TestBehavior extends EventsourcedStashReferenceManagement {

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
            onSignalCleanup.apply(ctx, signal); Behaviors.stopped[InternalProtocol]
        }
      )
    }
  }

  private def dummySettings(capacity: Int = 42) = new EventsourcedSettings {

    override def stashCapacity: Int = capacity

    override def logOnStashing: Boolean = ???

    override def stashOverflowStrategyConfigurator: String = ???

    override def recoveryEventTimeout: FiniteDuration = ???

    override def journalPluginId: String = ???

    override def withJournalPluginId(id: String): EventsourcedSettings = ???

    override def snapshotPluginId: String = ???

    override def withSnapshotPluginId(id: String): EventsourcedSettings = ???
  }
}
