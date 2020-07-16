/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.crdt

import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.scaladsl.{ ActiveActiveEventSourcing, Effect, EventSourcedBehavior }
import akka.persistence.typed.{ ActiveActiveBaseSpec, ReplicaId }
import akka.serialization.jackson.CborSerializable

object LwwSpec {

  import ActiveActiveBaseSpec._

  sealed trait Command
  final case class Update(item: String, timestamp: Long, error: ActorRef[String]) extends Command
  final case class Get(replyTo: ActorRef[Registry]) extends Command

  sealed trait Event extends CborSerializable
  final case class Changed(item: String, timestamp: LwwTime) extends Event

  final case class Registry(item: String, updatedTimestamp: LwwTime) extends CborSerializable

  object LwwRegistry {

    def apply(entityId: String, replica: ReplicaId): Behavior[Command] = {
      ActiveActiveEventSourcing.withSharedJournal(
        entityId,
        replica,
        AllReplicas,
        PersistenceTestKitReadJournal.Identifier) { aaContext =>
        EventSourcedBehavior[Command, Event, Registry](
          aaContext.persistenceId,
          Registry("", LwwTime(Long.MinValue, aaContext.replicaId)),
          (state, command) =>
            command match {
              case Update(s, timestmap, error) =>
                if (s == "") {
                  error ! "bad value"
                  Effect.none
                } else {
                  Effect.persist(Changed(s, state.updatedTimestamp.increase(timestmap, aaContext.replicaId)))
                }
              case Get(replyTo) =>
                replyTo ! state
                Effect.none
            },
          (state, event) =>
            event match {
              case Changed(s, timestamp) =>
                if (timestamp.isAfter(state.updatedTimestamp)) Registry(s, timestamp)
                else state
            })
      }
    }

  }
}

class LwwSpec extends ActiveActiveBaseSpec {
  import LwwSpec._
  import ActiveActiveBaseSpec._

  class Setup {
    val entityId = nextEntityId
    val r1 = spawn(LwwRegistry.apply(entityId, R1))
    val r2 = spawn(LwwRegistry.apply(entityId, R2))
    val r1Probe = createTestProbe[String]()
    val r2Probe = createTestProbe[String]()
    val r1GetProbe = createTestProbe[Registry]()
    val r2GetProbe = createTestProbe[Registry]()
  }

  "Lww Active Active Event Sourced Behavior" should {
    "replicate a single event" in new Setup {
      r1 ! Update("a1", 1L, r1Probe.ref)
      eventually {
        val probe = createTestProbe[Registry]()
        r2 ! Get(probe.ref)
        probe.expectMessage(Registry("a1", LwwTime(1L, R1)))
      }
    }

    "resolve conflict" in new Setup {
      r1 ! Update("a1", 1L, r1Probe.ref)
      r2 ! Update("b1", 2L, r2Probe.ref)
      eventually {
        r1 ! Get(r1GetProbe.ref)
        r2 ! Get(r2GetProbe.ref)
        r1GetProbe.expectMessage(Registry("b1", LwwTime(2L, R2)))
        r2GetProbe.expectMessage(Registry("b1", LwwTime(2L, R2)))
      }
    }

    "have deterministic tiebreak when the same time" in new Setup {
      r1 ! Update("a1", 1L, r1Probe.ref)
      r2 ! Update("b1", 1L, r2Probe.ref)
      // R1 < R2
      eventually {
        r1 ! Get(r1GetProbe.ref)
        r2 ! Get(r2GetProbe.ref)
        r1GetProbe.expectMessage(Registry("a1", LwwTime(1L, R1)))
        r2GetProbe.expectMessage(Registry("a1", LwwTime(1L, R1)))
      }
    }
  }

}
