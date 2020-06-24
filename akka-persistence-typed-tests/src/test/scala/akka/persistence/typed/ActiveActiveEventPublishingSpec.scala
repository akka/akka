/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.scaladsl.ActiveActiveEventSourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import org.scalatest.wordspec.AnyWordSpecLike

object ActiveActiveEventPublishingSpec {

  object MyActiveActive {
    trait Command
    case class Add(text: String, replyTo: ActorRef[Done]) extends Command
    case class Get(replyTo: ActorRef[Set[String]]) extends Command

    def apply(entityId: String, replicaId: String, allReplicas: Set[String]): Behavior[Command] =
      ActiveActiveEventSourcing(entityId, replicaId, allReplicas, PersistenceTestKitReadJournal.Identifier)(
        aactx =>
          EventSourcedBehavior[Command, String, Set[String]](
            aactx.persistenceId,
            Set.empty,
            (state, command) =>
              command match {
                case Add(string, replyTo) =>
                  Effect.persist(string).thenRun(_ => replyTo ! Done)
                case Get(replyTo) =>
                  replyTo ! state
                  Effect.none
              },
            (state, string) => state + string))
  }
}

class ActiveActiveEventPublishingSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config)
    with AnyWordSpecLike
    with LogCapturing {

  import ActiveActiveEventPublishingSpec._

  "An active active actor" must {
    "move forward when a published event from a replica is received" in {

      val actor = spawn(MyActiveActive("myId1", "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        Some("DC-B"),
        PersistenceId.replicatedUniqueId("myId1", "DC-B"),
        1L,
        "two",
        System.currentTimeMillis())
      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "two", "three"))
    }

    "ignore a published event from a replica is received but the sequence number is unexpected" in {
      val actor = spawn(MyActiveActive("myId2", "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        Some("DC-B"),
        PersistenceId.replicatedUniqueId("myId2", "DC-B"),
        2L, // missing 1L
        "two",
        System.currentTimeMillis())
      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "three"))
    }

    "ignore a published event from an unknown replica" in {
      val actor = spawn(MyActiveActive("myId3", "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        Some("DC-C"),
        PersistenceId.replicatedUniqueId("myId3", "DC-C"),
        1L,
        "two",
        System.currentTimeMillis())
      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "three"))
    }

    "ignore an already seen event from a replica" in {
      val actor = spawn(MyActiveActive("myId4", "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        Some("DC-B"),
        PersistenceId.replicatedUniqueId("myId4", "DC-B"),
        1L,
        "two",
        System.currentTimeMillis())
      // simulate another published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        Some("DC-B"),
        PersistenceId.replicatedUniqueId("myId4", "DC-B"),
        1L,
        "two-again", // ofc this would be the same in the real world, different just so we can detect
        System.currentTimeMillis())

      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "two", "three"))
    }

  }

}
