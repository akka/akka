/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedSequenceNumberSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
      akka.persistence.snapshot-store.plugin = "slow-snapshot-store"
      slow-snapshot-store.class = "${classOf[SlowInMemorySnapshotStore].getName}"
    """)

}

class EventSourcedSequenceNumberSpec
    extends ScalaTestWithActorTestKit(EventSourcedSequenceNumberSpec.conf)
    with WordSpecLike
    with LogCapturing {

  private def behavior(pid: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    Behaviors.setup(
      ctx =>
        EventSourcedBehavior[String, String, String](pid, "", {
          (state, command) =>
            state match {
              case "stashing" =>
                command match {
                  case "unstash" =>
                    probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} unstash"
                    Effect.persist("normal").thenUnstashAll()
                  case _ =>
                    Effect.stash()
                }
              case _ =>
                command match {
                  case "cmd" =>
                    probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} onCommand"
                    Effect
                      .persist(command)
                      .thenRun(_ => probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} thenRun")
                  case "stash" =>
                    probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} stash"
                    Effect.persist("stashing")
                  case "snapshot" =>
                    Effect.persist("snapshot")
                }
            }
        }, { (_, evt) =>
          probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} eventHandler"
          evt
        }).snapshotWhen((_, event, _) => event == "snapshot").receiveSignal {
          case (_, RecoveryCompleted) =>
            probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} onRecoveryComplete"
        })

  "The sequence number" must {

    "be accessible in the handlers" in {
      val probe = TestProbe[String]()
      val ref = spawn(behavior(PersistenceId.ofUniqueId("ess-1"), probe.ref))
      probe.expectMessage("0 onRecoveryComplete")

      ref ! "cmd"
      probe.expectMessage("0 onCommand")
      probe.expectMessage("0 eventHandler")
      probe.expectMessage("1 thenRun")
    }

    "be available while replaying stash" in {
      val probe = TestProbe[String]()
      val ref = spawn(behavior(PersistenceId.ofUniqueId("ess-2"), probe.ref))
      probe.expectMessage("0 onRecoveryComplete")

      ref ! "stash"
      ref ! "cmd"
      ref ! "cmd"
      ref ! "cmd"
      ref ! "unstash"
      probe.expectMessage("0 stash")
      probe.expectMessage("0 eventHandler")
      probe.expectMessage("1 unstash")
      probe.expectMessage("1 eventHandler")
      probe.expectMessage("2 onCommand")
      probe.expectMessage("2 eventHandler")
      probe.expectMessage("3 thenRun")
      probe.expectMessage("3 onCommand")
      probe.expectMessage("3 eventHandler")
      probe.expectMessage("4 thenRun")
    }

    // reproducer for #27935
    "not fail when snapshotting" in {
      val probe = TestProbe[String]()
      val ref = spawn(behavior(PersistenceId.ofUniqueId("ess-3"), probe.ref))
      probe.expectMessage("0 onRecoveryComplete")

      ref ! "cmd"
      ref ! "snapshot"
      ref ! "cmd"

      probe.expectMessage("0 onCommand") // first command
      probe.expectMessage("0 eventHandler")
      probe.expectMessage("1 thenRun")
      probe.expectMessage("1 eventHandler") // snapshot
      probe.expectMessage("2 onCommand") // second command
      probe.expectMessage("2 eventHandler")
      probe.expectMessage("3 thenRun")
    }
  }
}
