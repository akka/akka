/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.internal.{ ReplicatedPublishedEventMetaData, VersionVector }
import akka.persistence.typed.scaladsl.ActiveActiveEventSourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import org.scalatest.wordspec.AnyWordSpecLike

object ActiveActiveEventPublishingSpec {

  object MyActiveActive {
    trait Command
    case class Add(text: String, replyTo: ActorRef[Done]) extends Command
    case class Get(replyTo: ActorRef[Set[String]]) extends Command
    case object Stop extends Command

    def apply(entityId: String, replicaId: String, allReplicas: Set[String]): Behavior[Command] =
      Behaviors.setup { ctx =>
        ActiveActiveEventSourcing(entityId, replicaId, allReplicas, PersistenceTestKitReadJournal.Identifier)(
          aactx =>
            EventSourcedBehavior[Command, String, Set[String]](
              aactx.persistenceId,
              Set.empty,
              (state, command) =>
                command match {
                  case Add(string, replyTo) =>
                    ctx.log.debug("Persisting [{}]", string)
                    Effect.persist(string).thenRun { _ =>
                      ctx.log.debug("Ack:ing [{}]", string)
                      replyTo ! Done
                    }
                  case Get(replyTo) =>
                    replyTo ! state
                    Effect.none
                  case Stop =>
                    Effect.stop()
                },
              (state, string) => state + string))
      }
  }
}

class ActiveActiveEventPublishingSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config)
    with AnyWordSpecLike
    with LogCapturing {

  private var idCounter = 0
  def nextEntityId(): String = {
    idCounter += 1
    s"myId$idCounter"
  }

  import ActiveActiveEventPublishingSpec._

  "An active active actor" must {
    "move forward when a published event from a replica is received" in {
      val id = nextEntityId()
      val actor = spawn(MyActiveActive(id, "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-B"),
        1L,
        "two",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))
      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "two", "three"))
    }

    "ignore a published event from a replica is received but the sequence number is unexpected" in {
      val id = nextEntityId()
      val actor = spawn(MyActiveActive(id, "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-B"),
        2L, // missing 1L
        "two",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))
      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "three"))
    }

    "ignore a published event from an unknown replica" in {
      val id = nextEntityId()
      val actor = spawn(MyActiveActive(id, "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-C"),
        1L,
        "two",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-C", VersionVector.empty)))
      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "three"))
    }

    "ignore an already seen event from a replica" in {
      val id = nextEntityId()
      val actor = spawn(MyActiveActive(id, "DC-A", Set("DC-A", "DC-B")))
      val probe = createTestProbe[Any]()
      actor ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId("myId4", "DC-B"),
        1L,
        "two",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))
      // simulate another published event from that replica
      actor.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-B"),
        1L,
        "two-again", // ofc this would be the same in the real world, different just so we can detect
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))

      actor ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      actor ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "two", "three"))
    }

    "handle published events after replay" in {
      val id = nextEntityId()
      val probe = createTestProbe[Any]()
      val activeActiveBehavior = MyActiveActive(id, "DC-A", Set("DC-A", "DC-B"))
      val incarnation1 = spawn(activeActiveBehavior)
      incarnation1 ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      incarnation1 ! MyActiveActive.Stop
      probe.expectTerminated(incarnation1)

      val incarnation2 = spawn(activeActiveBehavior)

      incarnation2 ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one"))
      // replay completed

      // simulate a published event from another replica
      incarnation2.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-B"),
        1L,
        "two",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))

      incarnation2 ! MyActiveActive.Add("three", probe.ref)
      probe.expectMessage(Done)

      incarnation2 ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "two", "three"))
    }

    "handle published events before and after replay" in {
      val id = nextEntityId()
      val probe = createTestProbe[Any]()
      val activeActiveBehaviorA = MyActiveActive(id, "DC-A", Set("DC-A", "DC-B"))
      val incarnationA1 = spawn(activeActiveBehaviorA)
      incarnationA1 ! MyActiveActive.Add("one", probe.ref)
      probe.expectMessage(Done)

      // simulate a published event from another replica
      incarnationA1.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-B"),
        1L,
        "two",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))

      incarnationA1 ! MyActiveActive.Stop
      probe.expectTerminated(incarnationA1)

      val incarnationA2 = spawn(activeActiveBehaviorA)

      // simulate a published event from another replica
      incarnationA2.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
        PersistenceId.replicatedUniqueId(id, "DC-B"),
        2L,
        "three",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData("DC-B", VersionVector.empty)))

      incarnationA2 ! MyActiveActive.Add("four", probe.ref)
      probe.expectMessage(Done)

      incarnationA2 ! MyActiveActive.Get(probe.ref)
      probe.expectMessage(Set("one", "two", "three", "four"))
    }

  }

}
