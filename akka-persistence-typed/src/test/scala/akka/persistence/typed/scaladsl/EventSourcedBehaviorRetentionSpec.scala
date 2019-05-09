/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.DeleteEventsCompleted
import akka.persistence.typed.DeleteSnapshotsCompleted
import akka.persistence.typed.DeletionTarget
import akka.persistence.typed.EventSourcedSignal
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.SnapshotMetadata
import akka.persistence.typed.SnapshotSelectionCriteria
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import akka.util.unused
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedBehaviorRetentionSpec {

  def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.loggers = [akka.testkit.TestEventListener]
    # akka.persistence.typed.log-stashing = on
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    """)

  sealed trait Command
  final case object Increment extends Command
  final case class IncrementWithPersistAll(nr: Int) extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  final case object StopIt extends Command

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  def counter(persistenceId: PersistenceId)(implicit system: ActorSystem[_]): Behavior[Command] =
    Behaviors.setup(ctx => counter(ctx, persistenceId))

  def counter(ctx: ActorContext[Command], persistenceId: PersistenceId)(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      probe = TestProbe[(State, Event)].ref,
      snapshotProbe = TestProbe[Try[SnapshotMetadata]].ref,
      retentionProbe = TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: ActorRef[(State, Event)],
      snapshotProbe: ActorRef[Try[SnapshotMetadata]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, probe, snapshotProbe, TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithProbe(ctx: ActorContext[Command], persistenceId: PersistenceId, probe: ActorRef[(State, Event)])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, probe, TestProbe[Try[SnapshotMetadata]].ref, TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithSnapshotProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: ActorRef[Try[SnapshotMetadata]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      TestProbe[(State, Event)].ref,
      snapshotProbe = probe,
      TestProbe[Try[EventSourcedSignal]].ref)

  def counterWithSnapshotAndRetentionProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probeS: ActorRef[Try[SnapshotMetadata]],
      probeR: ActorRef[Try[EventSourcedSignal]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, TestProbe[(State, Event)].ref, snapshotProbe = probeS, retentionProbe = probeR)

  def counter(
      @unused ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: ActorRef[(State, Event)],
      snapshotProbe: ActorRef[Try[SnapshotMetadata]],
      retentionProbe: ActorRef[Try[EventSourcedSignal]]): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId,
      emptyState = State(0, Vector.empty),
      commandHandler = (state, cmd) =>
        cmd match {
          case Increment =>
            Effect.persist(Incremented(1))

          case IncrementWithPersistAll(n) =>
            Effect.persist((0 until n).map(_ => Incremented(1)))

          case GetValue(replyTo) =>
            replyTo ! state
            Effect.none

          case StopIt =>
            Effect.none.thenStop()

        },
      eventHandler = (state, evt) =>
        evt match {
          case Incremented(delta) =>
            probe ! ((state, evt))
            State(state.value + delta, state.history :+ state.value)
        }).receiveSignal {
      case (_, RecoveryCompleted) => ()
      case (_, SnapshotCompleted(metadata)) =>
        snapshotProbe ! Success(metadata)
      case (_, SnapshotFailed(_, failure)) =>
        snapshotProbe ! Failure(failure)
      case (_, e: EventSourcedSignal) =>
        retentionProbe ! Success(e)
    }
  }
}

class EventSourcedBehaviorRetentionSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorRetentionSpec.conf)
    with WordSpecLike {

  import EventSourcedBehaviorRetentionSpec._
  import akka.actor.typed.scaladsl.adapter._

  // needed for the untyped event filter
  implicit val actorSystem = system.toUntyped

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  actorSystem.eventStream.publish(Mute(EventFilter.info(pattern = ".*was not delivered.*", occurrences = 100)))
  actorSystem.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 100)))

  "EventSourcedBehavior with retention" must {

    "snapshot every N sequence nrs" in {
      val pid = nextPid()
      val c = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! StopIt
      watchProbe.expectMessage("Terminated")

      // no snapshot should have happened
      val probeC2 = TestProbe[(State, Event)]()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val c2 = spawn(
        Behaviors.setup[Command](ctx =>
          counterWithProbe(ctx, pid, probeC2.ref, snapshotProbe.ref)
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      probeC2.expectMessage[(State, Event)]((State(0, Vector()), Incremented(1)))
      val watchProbeC2 = watcher(c2)
      c2 ! Increment
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(2)
      c2 ! StopIt
      watchProbeC2.expectMessage("Terminated")

      val probeC3 = TestProbe[(State, Event)]()
      val c3 = spawn(
        Behaviors.setup[Command](ctx =>
          counterWithProbe(ctx, pid, probeC3.ref)
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      // this time it should have been snapshotted so no events to replay
      probeC3.expectNoMessage()
      c3 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "snapshot every N sequence nrs when persisting multiple events" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val c =
        spawn(
          Behaviors.setup[Command](ctx =>
            counterWithSnapshotProbe(ctx, pid, snapshotProbe.ref)
              .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      // snapshot at seqNr 3 because of persistAll
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      c ! StopIt
      watchProbe.expectMessage("Terminated")

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(
        Behaviors.setup[Command](ctx =>
          counterWithProbe(ctx, pid, probeC2.ref)
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    "snapshot via predicate" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]
      val alwaysSnapshot: Behavior[Command] =
        Behaviors.setup { ctx =>
          counterWithSnapshotProbe(ctx, pid, snapshotProbe.ref).snapshotWhen { (_, _, _) =>
            true
          }
        }
      val c = spawn(alwaysSnapshot)
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! Increment
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(1)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! StopIt
      watchProbe.expectMessage("Terminated")

      val probe = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probe.ref)))
      // state should be rebuilt from snapshot, no events replayed
      // Fails as snapshot is async (i think)
      probe.expectNoMessage()
      c2 ! Increment
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "check all events for snapshot in PersistAll" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]
      val snapshotAtTwo = Behaviors.setup[Command](ctx =>
        counterWithSnapshotProbe(ctx, pid, snapshotProbe.ref).snapshotWhen { (s, _, _) =>
          s.value == 2
        })
      val c: ActorRef[Command] = spawn(snapshotAtTwo)
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)

      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      // snapshot at seqNr 3 because of persistAll
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      c ! StopIt
      watchProbe.expectMessage("Terminated")

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counterWithProbe(ctx, pid, probeC2.ref)))
      // middle event triggered all to be snapshot
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    def expectDeleteSnapshotCompleted(
        retentionProbe: TestProbe[Try[EventSourcedSignal]],
        maxSequenceNr: Long,
        minSequenceNr: Long): Unit = {
      retentionProbe.expectMessageType[Success[DeleteSnapshotsCompleted]].value should ===(
        DeleteSnapshotsCompleted(DeletionTarget.Criteria(
          SnapshotSelectionCriteria.latest.withMaxSequenceNr(maxSequenceNr).withMinSequenceNr(minSequenceNr))))
    }

    "delete snapshots automatically, based on criteria" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref)
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 3, keepNSnapshots = 2))))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(6)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(9)
      expectDeleteSnapshotCompleted(retentionProbe, 3, 0)

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(20, (0 until 20).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(12)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(15)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(18)
      expectDeleteSnapshotCompleted(retentionProbe, 6, 0)
      expectDeleteSnapshotCompleted(retentionProbe, 9, 3)
      expectDeleteSnapshotCompleted(retentionProbe, 12, 6)

      retentionProbe.expectNoMessage()
    }

    "optionally delete both old events and snapshots" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](
          ctx =>
            counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref).withRetention(
              // tests the Java API as well
              RetentionCriteria.snapshotEvery(numberOfEvents = 3, keepNSnapshots = 2).withDeleteEventsOnSnapshot)))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(6)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(9)

      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 3
      // Note that when triggering deletion of snapshots from deletion of events it is intentionally "off by one".
      // The reason for -1 is that a snapshot at the exact toSequenceNr is still useful and the events
      // after that can be replayed after that snapshot, but replaying the events after toSequenceNr without
      // starting at the snapshot at toSequenceNr would be invalid.
      expectDeleteSnapshotCompleted(retentionProbe, 2, 0)

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(20, (0 until 20).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(12)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(15)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(18)

      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      expectDeleteSnapshotCompleted(retentionProbe, 5, 0)

      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 9
      expectDeleteSnapshotCompleted(retentionProbe, 8, 2)

      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 12
      expectDeleteSnapshotCompleted(retentionProbe, 11, 5)

      retentionProbe.expectNoMessage()
    }

    "be possible to combine snapshotWhen and retention criteria" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](
          ctx =>
            counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref)
              .snapshotWhen((_, _, seqNr) => seqNr == 3 || seqNr == 13)
              .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 5, keepNSnapshots = 1))))

      (1 to 3).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, (0 until 3).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      retentionProbe.expectNoMessage()

      (4 to 10).foreach(_ => persistentActor ! Increment)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(5)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(10)
      expectDeleteSnapshotCompleted(retentionProbe, 5, 0)

      (11 to 13).foreach(_ => persistentActor ! Increment)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(13)
      // no deletes triggered by snapshotWhen
      retentionProbe.expectNoMessage()

      (14 to 16).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(16, (0 until 16).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(15)
      expectDeleteSnapshotCompleted(retentionProbe, 10, 5)
      retentionProbe.expectNoMessage()
    }

    "be possible to combine snapshotWhen and retention criteria withDeleteEventsOnSnapshot" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](
          ctx =>
            counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref)
              .snapshotWhen((_, _, seqNr) => seqNr == 3 || seqNr == 13)
              .withRetention(
                RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 3).withDeleteEventsOnSnapshot)))

      (1 to 3).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, (0 until 3).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(2)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      retentionProbe.expectNoMessage()

      (4 to 10).foreach(_ => persistentActor ! Increment)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(4)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(6)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(8)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 2
      expectDeleteSnapshotCompleted(retentionProbe, 1, 0)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(10)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 4
      expectDeleteSnapshotCompleted(retentionProbe, 3, 0)

      (11 to 13).foreach(_ => persistentActor ! Increment)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(12)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      expectDeleteSnapshotCompleted(retentionProbe, 5, 0)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(13)
      // no deletes triggered by snapshotWhen
      retentionProbe.expectNoMessage()

      (14 to 16).foreach(_ => persistentActor ! Increment)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(14)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 8
      expectDeleteSnapshotCompleted(retentionProbe, 7, 1)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(16)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 10
      expectDeleteSnapshotCompleted(retentionProbe, 9, 3)
      retentionProbe.expectNoMessage()
    }

    "be possible to snapshot every event" in {
      // very bad idea to snapshot every event, but technically possible
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref)
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 1, keepNSnapshots = 3))))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(1)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(2)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(4)
      expectDeleteSnapshotCompleted(retentionProbe, 1, 0)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(5)
      expectDeleteSnapshotCompleted(retentionProbe, 2, 0)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(6)
      expectDeleteSnapshotCompleted(retentionProbe, 3, 0)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(7)
      expectDeleteSnapshotCompleted(retentionProbe, 4, 1)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(8)
      expectDeleteSnapshotCompleted(retentionProbe, 5, 2)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(9)
      expectDeleteSnapshotCompleted(retentionProbe, 6, 3)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(10)
      expectDeleteSnapshotCompleted(retentionProbe, 7, 4)
    }

    "be possible to snapshot every event withDeleteEventsOnSnapshot" in {
      // very bad idea to snapshot every event, but technically possible
      val pid = nextPid()
      val snapshotProbe = TestProbe[Try[SnapshotMetadata]]()
      val retentionProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counterWithSnapshotAndRetentionProbe(ctx, pid, snapshotProbe.ref, retentionProbe.ref).withRetention(
            RetentionCriteria.snapshotEvery(numberOfEvents = 1, keepNSnapshots = 3).withDeleteEventsOnSnapshot)))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(1)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(2)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(3)
      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(4)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 1

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(5)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 2
      expectDeleteSnapshotCompleted(retentionProbe, 1, 0)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(6)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 3
      expectDeleteSnapshotCompleted(retentionProbe, 2, 0)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(7)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 4
      expectDeleteSnapshotCompleted(retentionProbe, 3, 0)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(8)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 5
      expectDeleteSnapshotCompleted(retentionProbe, 4, 1)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(9)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      expectDeleteSnapshotCompleted(retentionProbe, 5, 2)

      snapshotProbe.expectMessageType[Success[SnapshotMetadata]].value.sequenceNr should ===(10)
      retentionProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 7
      expectDeleteSnapshotCompleted(retentionProbe, 6, 3)
    }

    def watcher(toWatch: ActorRef[_]): TestProbe[String] = {
      val probe = TestProbe[String]()
      val w = Behaviors.setup[Any] { ctx =>
        ctx.watch(toWatch)
        Behaviors
          .receive[Any] { (_, _) =>
            Behaviors.same
          }
          .receiveSignal {
            case (_, _: Terminated) =>
              probe.ref ! "Terminated"
              Behaviors.stopped
          }
      }
      spawn(w)
      probe
    }
  }
}
