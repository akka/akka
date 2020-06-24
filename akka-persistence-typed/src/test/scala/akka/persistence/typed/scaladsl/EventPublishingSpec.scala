/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.PublishedEvent
import akka.persistence.typed.scaladsl.EventPublishingSpec.WowSuchEventSourcingBehavior
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object EventPublishingSpec {
  def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)

  object WowSuchEventSourcingBehavior {
    sealed trait Command
    case class StoreThis(data: String, tagIt: Boolean, replyTo: ActorRef[Done]) extends Command

    final case class Event(data: String, tagIt: Boolean)

    def apply(id: PersistenceId): Behavior[Command] =
      EventSourcedBehavior[Command, Event, Set[Event]](
        id,
        Set.empty,
        (_, command) =>
          command match {
            case StoreThis(data, tagIt, replyTo) =>
              Effect.persist(Event(data, tagIt)).thenRun(_ => replyTo ! Done)
          },
        (state, event) => state + event)
        .withTagger(evt => if (evt.tagIt) Set("tag") else Set.empty)
        .withEvenPublishing()
  }
}

class EventPublishingSpec
    extends ScalaTestWithActorTestKit(EventPublishingSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  "EventPublishing support" must {

    "publish events after written for any actor" in {
      val topicProbe = createTestProbe[Any]()
      system.eventStream ! EventStream.Subscribe[PublishedEvent](topicProbe.ref.narrow)
      // We don't verify subscription completed (no ack available), but expect the next steps to take enough time
      // for subscription to complete

      val myId = PersistenceId.ofUniqueId("myId")
      val wowSuchActor = spawn(WowSuchEventSourcingBehavior(myId))

      val persistProbe = createTestProbe[Any]()
      wowSuchActor ! WowSuchEventSourcingBehavior.StoreThis("great stuff", tagIt = false, replyTo = persistProbe.ref)
      persistProbe.expectMessage(Done)

      val published1 = topicProbe.expectMessageType[PublishedEvent]
      published1.persistenceId should ===(myId)
      published1.event should ===(WowSuchEventSourcingBehavior.Event("great stuff", false))
      published1.sequenceNumber should ===(1L)
      published1.tags should ===(Set.empty)

      val anotherId = PersistenceId.ofUniqueId("anotherId")
      val anotherActor = spawn(WowSuchEventSourcingBehavior(anotherId))
      anotherActor ! WowSuchEventSourcingBehavior.StoreThis("another event", tagIt = true, replyTo = persistProbe.ref)
      persistProbe.expectMessage(Done)

      val published2 = topicProbe.expectMessageType[PublishedEvent]
      published2.persistenceId should ===(anotherId)
      published2.event should ===(WowSuchEventSourcingBehavior.Event("another event", true))
      published2.sequenceNumber should ===(1L)
      published2.tags should ===(Set("tag"))
    }

  }

}
