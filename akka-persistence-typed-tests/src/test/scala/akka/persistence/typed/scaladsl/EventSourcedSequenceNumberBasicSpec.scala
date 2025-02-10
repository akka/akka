/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted

object EventSourcedSequenceNumberBasicSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
      akka.persistence.snapshot-store.plugin = "slow-snapshot-store"
      slow-snapshot-store.class = "${classOf[SlowInMemorySnapshotStore].getName}"
    """)

}

class EventSourcedSequenceNumberBasicSpec
    extends ScalaTestWithActorTestKit(EventSourcedSequenceNumberBasicSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  private def behavior(testOnRecoveryComplete: Boolean, pid: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    Behaviors.setup(
      ctx =>
        EventSourcedBehavior[String, String, String](
          pid,
          "", { (_, command) =>
            probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} commandHandler $command"
            Effect.persist("evt")
          }, { (_, evt) =>
            probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} eventHandler $evt"
            evt
          }).snapshotWhen((_, event, _) => event == "snapshot").receiveSignal {
          case (_, RecoveryCompleted) =>
            if (testOnRecoveryComplete) {
              probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} onRecoveryComplete"
            }
        })

  def runTheSpec(testOnRecoveryComplete: Boolean, id: String): Unit = {
    val probe = TestProbe[String]()
    val ref = spawn(behavior(testOnRecoveryComplete, PersistenceId.ofUniqueId(s"ess-$id"), probe.ref))

    if (testOnRecoveryComplete) {
      //entering here seems to block and let the EventSourcedBehavior recover properly
      probe.expectMessage("0 onRecoveryComplete")
    }

    ref ! "cmd"
    probe.expectMessage("0 commandHandler cmd")
    probe.expectMessage("1 eventHandler evt")

    ref ! "cmd"
    probe.expectMessage("1 commandHandler cmd")
    probe.expectMessage("2 eventHandler evt")

    testKit.stop(ref)
    probe.expectTerminated(ref)
  }

  // reproducer for #32359
  "The sequence number" must {
    "be accessible accessible when testing RecoveryCompleted" in {
      runTheSpec(true, "1")
    }

    "be accessible accessible when NOT testing RecoveryCompleted" in {
      runTheSpec(false, "1")
    }
  }
}
