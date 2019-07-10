/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.query.{EventEnvelope, PersistenceQuery, Sequence}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.typed.PersistenceId
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import org.scalatest.WordSpecLike


class EventSourcedSnapshotAdapterSpec
    extends ScalaTestWithActorTestKit(EventSourcedEventAdapterSpec.conf)
    with WordSpecLike {
  import EventSourcedBehaviorSpec._
  import EventSourcedEventAdapterSpec._
  import akka.actor.typed.scaladsl.adapter._
  system.toUntyped.eventStream.publish(Mute(EventFilter.warning(start = "No default snapshot store", occurrences = 1)))

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")
  implicit val materializer = ActorMaterializer()(system.toUntyped)
  val queries: LeveldbReadJournal = PersistenceQuery(system.toUntyped).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  private def behavior(pid: PersistenceId, probe: ActorRef[String]): EventSourcedBehavior[String, String, String] =
    EventSourcedBehavior(pid, "", commandHandler = { (_, command) =>
      Effect.persist(command).thenRun(newState => probe ! newState)
    }, eventHandler = { (state, evt) =>
      state + evt
    })

  "Snapshot adapter" must {

    "wrap single snaphots" in {
        pending
    }

    "filter unused snapshots" in {
      pending
    }

    "split one into several" in {
      val probe = TestProbe[String]()
      val pid = nextPid()
      val ref = spawn(behavior(pid, probe.ref).eventAdapter(new SplitEventAdapter))

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("a")
      probe.expectMessage("abc")

      // replay
      val ref2 = spawn(behavior(pid, probe.ref).eventAdapter(new SplitEventAdapter))
      ref2 ! "d"
      probe.expectMessage("<A><B><C>d")
    }

    "support manifest" in {
      val probe = TestProbe[String]()
      val pid = nextPid()
      val ref = spawn(behavior(pid, probe.ref).eventAdapter(new EventAdapterWithManifest))

      ref ! "a"
      ref ! "bcd"
      probe.expectMessage("a")
      probe.expectMessage("abcd")

      // replay
      val ref2 = spawn(behavior(pid, probe.ref).eventAdapter(new EventAdapterWithManifest))
      ref2 ! "e"
      probe.expectMessage("A1BCD3e")
    }

    "adapt events" in {
      val pid = nextPid()
      val c = spawn(Behaviors.setup[Command] { ctx =>
        val persistentBehavior = counter(ctx, pid)

        persistentBehavior.eventAdapter(new GenericWrapperEventAdapter[Event])
      })
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))))

      val c2 =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

    }

    "adapter multiple events with persist all" in {
      val pid = nextPid()
      val c =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(2)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(
        EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))),
        EventEnvelope(Sequence(2), pid.id, 2, GenericWrapper(Incremented(1))))

      val c2 =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "adapt and tag events" in {
      val pid = nextPid()
      val c = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid).withTagger(_ => Set("tag99")).eventAdapter(new GenericWrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))))

      val c2 =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val taggedEvents = queries.currentEventsByTag("tag99").runWith(Sink.seq).futureValue
      taggedEvents shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))))
    }
  }
}
