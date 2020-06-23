/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.aa
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.query.journal.inmem.scaladsl.InmemReadJournal
import akka.persistence.typed.scaladsl.{ ActiveActiveEventSourcing, Effect, EventSourcedBehavior }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

object ActiveActiveSpec {

  val config = ConfigFactory.parseString("""
       akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      """)

  val AllReplicas = Set("R1", "R2", "R3")

  sealed trait Command
  case class GetState(replyTo: ActorRef[State]) extends Command
  case class StoreMe(description: String, replyTo: ActorRef[Done]) extends Command
  case class GetReplica(replyTo: ActorRef[(String, Set[String])]) extends Command

  case class State(all: List[String])
  def testBehavior(entityId: String, replicaId: String, probe: ActorRef[EventAndContext]): Behavior[Command] =
    testBehavior(entityId, replicaId, Some(probe))

  def testBehavior(
      entityId: String,
      replicaId: String,
      probe: Option[ActorRef[EventAndContext]] = None): Behavior[Command] =
    ActiveActiveEventSourcing(entityId, replicaId, AllReplicas, InmemReadJournal.Identifier)(
      aaContext =>
        EventSourcedBehavior[Command, String, State](
          aaContext.persistenceId,
          State(Nil),
          (state, command) =>
            command match {
              case GetState(replyTo) =>
                replyTo ! state
                Effect.none
              case GetReplica(replyTo) =>
                replyTo.tell((aaContext.replicaId, aaContext.allReplicas))
                Effect.none
              case StoreMe(evt, ack) =>
                Effect.persist(evt).thenRun(_ => ack ! Done)
            },
          (state, event) => {
            probe.foreach(_ ! EventAndContext(event, aaContext.origin))
            state.copy(all = event :: state.all)
          }))

}

case class EventAndContext(event: Any, origin: String)

class ActiveActiveSpec
    extends ScalaTestWithActorTestKit(ActiveActiveSpec.config)
    with AnyWordSpecLike
    with LogCapturing
    with Eventually {
  import ActiveActiveSpec._
  val ids = new AtomicInteger(0)
  def nextEntityId = s"e-${ids.getAndIncrement()}"
  "ActiveActiveEventSourcing" should {
    "replicate events between entities" in {
      val entityId = nextEntityId
      val probe = createTestProbe[Done]()
      val r1 = spawn(testBehavior(entityId, "R1"))
      val r2 = spawn(testBehavior(entityId, "R2"))
      r1 ! StoreMe("from r1", probe.ref)
      r2 ! StoreMe("from r2", probe.ref)
      eventually {
        val probe = createTestProbe[State]()
        r1 ! GetState(probe.ref)
        probe.expectMessageType[State].all.toSet shouldEqual Set("from r1", "from r2")
      }
      eventually {
        val probe = createTestProbe[State]()
        r2 ! GetState(probe.ref)
        probe.expectMessageType[State].all.toSet shouldEqual Set("from r1", "from r2")
      }
    }
    "get all events in recovery" in {
      val entityId = nextEntityId
      val probe = createTestProbe[Done]()
      val r1 = spawn(testBehavior(entityId, "R1"))
      val r2 = spawn(testBehavior(entityId, "R2"))
      r1 ! StoreMe("from r1", probe.ref)
      r2 ! StoreMe("from r2", probe.ref)
      r1 ! StoreMe("from r1 again", probe.ref)

      val r3 = spawn(testBehavior(entityId, "R3"))
      eventually {
        val probe = createTestProbe[State]()
        r3 ! GetState(probe.ref)
        probe.expectMessageType[State].all.toSet shouldEqual Set("from r1", "from r2", "from r1 again")
      }
    }

    "have access to replica information" in {
      val entityId = nextEntityId
      val probe = createTestProbe[(String, Set[String])]()
      val r1 = spawn(testBehavior(entityId, "R1"))
      r1 ! GetReplica(probe.ref)
      probe.expectMessage(("R1", Set("R1", "R2", "R3")))
    }

    "have access to event origin" in {
      val entityId = nextEntityId
      val replyProbe = createTestProbe[Done]()
      val eventProbeR1 = createTestProbe[EventAndContext]()
      val eventProbeR2 = createTestProbe[EventAndContext]()

      val r1 = spawn(testBehavior(entityId, "R1", eventProbeR1.ref))
      val r2 = spawn(testBehavior(entityId, "R2", eventProbeR2.ref))

      r1 ! StoreMe("from r1", replyProbe.ref)
      eventProbeR2.expectMessage(EventAndContext("from r1", "R1"))
      eventProbeR1.expectMessage(EventAndContext("from r1", "R1"))

      r2 ! StoreMe("from r2", replyProbe.ref)
      eventProbeR1.expectMessage(EventAndContext("from r2", "R2"))
      eventProbeR2.expectMessage(EventAndContext("from r2", "R2"))

    }
  }
}
