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
import akka.persistence.typed.DeleteSnapshotsFailed
import akka.persistence.typed.DeletionTarget
import akka.persistence.typed.EventSourcedSignal
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.SnapshotMetadata
import akka.persistence.typed.SnapshotSelectionCriteria
import akka.serialization.jackson.CborSerializable
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import akka.util.unused
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedBehaviorRetentionSpec {

  def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    # akka.persistence.typed.log-stashing = on
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    """)

  sealed trait Command extends CborSerializable
  final case object Increment extends Command
  final case class IncrementWithPersistAll(nr: Int) extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  final case object StopIt extends Command

  sealed trait Event extends CborSerializable
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int]) extends CborSerializable

  def counter(
      @unused ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: Option[ActorRef[(State, Event)]] = None,
      snapshotSignalProbe: Option[ActorRef[EventSourcedSignal]] = None,
      eventSignalProbe: Option[ActorRef[Try[EventSourcedSignal]]] = None)
    : EventSourcedBehavior[Command, Event, State] = {
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
            probe.foreach(_ ! ((state, evt)))
            State(state.value + delta, state.history :+ state.value)
      }).receiveSignal {
      case (_, RecoveryCompleted) => ()
      case (_, sc: SnapshotCompleted) =>
        println(s"${System.currentTimeMillis()} snapshot completed: $sc, telling $snapshotSignalProbe")
        snapshotSignalProbe.foreach(_ ! sc)
      case (_, sf: SnapshotFailed) =>
        println(s"snapshot failed: $sf, telling $snapshotSignalProbe")
        snapshotSignalProbe.foreach(_ ! sf)
      case (_, dc: DeleteSnapshotsCompleted) =>
        println(s"delete snapshot completed: $dc, telling $snapshotSignalProbe")
        snapshotSignalProbe.foreach(_ ! dc)
      case (_, dsf: DeleteSnapshotsFailed) =>
        println(s"delete snapshot failed: $dsf, telling $snapshotSignalProbe")
        snapshotSignalProbe.foreach(_ ! dsf)
      case (_, e: EventSourcedSignal) =>
        println(s"other signal: $e, telling $eventSignalProbe")
        eventSignalProbe.foreach(_ ! Success(e))
    }
  }
}

class EventSourcedBehaviorRetentionSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorRetentionSpec.conf)
    with WordSpecLike
    with LogCapturing {

  import EventSourcedBehaviorRetentionSpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  "EventSourcedBehavior with retention" must {

    "snapshot every N sequence nrs" in {
      val pid = nextPid()
      val c = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))

      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! StopIt
      val watchProbe = TestProbe()
      watchProbe.expectTerminated(c)

      // no snapshot should have happened
      val probeC2 = TestProbe[(State, Event)]()
      val stupidProbe = createTestProbe[AnyRef]()
      stupidProbe.ref ! "bjöö"
      stupidProbe.expectMessage("bjöö") // passes
      stupidProbe.ref ! "bjöööö"
      stupidProbe.expectMessageType[String] // passes
      stupidProbe.ref ! SnapshotCompleted(SnapshotMetadata("a", 1, 1))
      stupidProbe.expectMessageType[SnapshotCompleted] // fails here

      val c2 = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, probe = Some(probeC2.ref), snapshotSignalProbe = Some(stupidProbe.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      probeC2.expectMessage[(State, Event)]((State(0, Vector()), Incremented(1)))

      c2 ! Increment
      println(s"${System.currentTimeMillis()} waiting for snapshot probe ${stupidProbe.ref}")
      stupidProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(2)
      c2 ! StopIt
      watchProbe.expectTerminated(c2)

      val probeC3 = TestProbe[(State, Event)]()
      val c3 = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, Some(probeC3.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      // this time it should have been snapshotted so no events to replay
      probeC3.expectNoMessage()
      c3 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "snapshot every N sequence nrs when persisting multiple events" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val c =
        spawn(
          Behaviors.setup[Command](ctx =>
            counter(ctx, pid, None, Some(snapshotSignalProbe.ref))
              .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      // snapshot at seqNr 3 because of persistAll
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      c ! StopIt
      val watchProbe = TestProbe()
      watchProbe.expectTerminated(c)

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, Some(probeC2.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    "snapshot via predicate" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]
      val alwaysSnapshot: Behavior[Command] =
        Behaviors.setup { ctx =>
          counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref)).snapshotWhen { (_, _, _) =>
            true
          }
        }
      val c = spawn(alwaysSnapshot)
      val replyProbe = TestProbe[State]()

      c ! Increment
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(1)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! StopIt
      val watchProbe = TestProbe()
      watchProbe.expectTerminated(c)

      val probe = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid, Some(probe.ref))))
      // state should be rebuilt from snapshot, no events replayed
      // Fails as snapshot is async (i think)
      probe.expectNoMessage()
      c2 ! Increment
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "check all events for snapshot in PersistAll" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]
      val snapshotAtTwo = Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref)).snapshotWhen { (s, _, _) =>
          s.value == 2
      })
      val c: ActorRef[Command] = spawn(snapshotAtTwo)

      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)

      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      // snapshot at seqNr 3 because of persistAll
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      c ! StopIt
      val watchProbe = TestProbe()
      watchProbe.expectTerminated(c)

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid, Some(probeC2.ref))))
      // middle event triggered all to be snapshot
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    def expectDeleteSnapshotCompleted(
        retentionProbe: TestProbe[EventSourcedSignal],
        maxSequenceNr: Long,
        minSequenceNr: Long): Unit = {
      retentionProbe.expectMessageType[DeleteSnapshotsCompleted].target should ===(
        DeleteSnapshotsCompleted(DeletionTarget.Criteria(
          SnapshotSelectionCriteria.latest.withMaxSequenceNr(maxSequenceNr).withMinSequenceNr(minSequenceNr))))
    }

    "delete snapshots automatically, based on criteria" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 3, keepNSnapshots = 2))))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(6)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(9)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 3, 0)

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(20, (0 until 20).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(12)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(15)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(18)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 6, 0)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 9, 3)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 12, 6)

      snapshotSignalProbe.expectNoMessage()
    }

    "optionally delete both old events and snapshots" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val eventProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref), eventSignalProbe = Some(eventProbe.ref))
          .withRetention(
            // tests the Java API as well
            RetentionCriteria.snapshotEvery(numberOfEvents = 3, keepNSnapshots = 2).withDeleteEventsOnSnapshot)))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(6)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(9)

      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 3
      // Note that when triggering deletion of snapshots from deletion of events it is intentionally "off by one".
      // The reason for -1 is that a snapshot at the exact toSequenceNr is still useful and the events
      // after that can be replayed after that snapshot, but replaying the events after toSequenceNr without
      // starting at the snapshot at toSequenceNr would be invalid.
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 2, 0)

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(20, (0 until 20).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(12)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(15)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(18)

      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 5, 0)

      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 9
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 8, 2)

      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 12
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 11, 5)

      eventProbe.expectNoMessage()
      snapshotSignalProbe.expectNoMessage()
    }

    "be possible to combine snapshotWhen and retention criteria" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val eventProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref), eventSignalProbe = Some(eventProbe.ref))
          .snapshotWhen((_, _, seqNr) => seqNr == 3 || seqNr == 13)
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 5, keepNSnapshots = 1))))

      (1 to 3).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, (0 until 3).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      eventProbe.expectNoMessage()

      (4 to 10).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(5)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(10)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 5, 0)

      (11 to 13).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(13)
      // no deletes triggered by snapshotWhen
      eventProbe.expectNoMessage()

      (14 to 16).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(16, (0 until 16).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(15)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 10, 5)
      eventProbe.expectNoMessage()
    }

    "be possible to combine snapshotWhen and retention criteria withDeleteEventsOnSnapshot" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val eventProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref), eventSignalProbe = Some(eventProbe.ref))
          .snapshotWhen((_, _, seqNr) => seqNr == 3 || seqNr == 13)
          .withRetention(
            RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 3).withDeleteEventsOnSnapshot)))

      (1 to 3).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, (0 until 3).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(2)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      eventProbe.expectNoMessage()

      (4 to 10).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(4)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(6)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(8)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 2
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 1, 0)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(10)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 4
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 3, 0)

      (11 to 13).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(12)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 5, 0)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(13)
      // no deletes triggered by snapshotWhen
      eventProbe.expectNoMessage()

      (14 to 16).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(14)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 8
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 7, 1)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(16)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 10
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 9, 3)
      eventProbe.expectNoMessage()
    }

    "be possible to snapshot every event" in {
      // very bad idea to snapshot every event, but technically possible
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 1, keepNSnapshots = 3))))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(1)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(2)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(4)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 1, 0)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(5)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 2, 0)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(6)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 3, 0)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(7)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 4, 1)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(8)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 5, 2)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(9)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 6, 3)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(10)
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 7, 4)
    }

    "be possible to snapshot every event withDeleteEventsOnSnapshot" in {
      // very bad idea to snapshot every event, but technically possible
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[EventSourcedSignal]()
      val eventProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref), eventSignalProbe = Some(eventProbe.ref))
          .withRetention(
            RetentionCriteria.snapshotEvery(numberOfEvents = 1, keepNSnapshots = 3).withDeleteEventsOnSnapshot)))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(1)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(2)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(3)
      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(4)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 1

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(5)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 2
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 1, 0)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(6)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 3
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 2, 0)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(7)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 4
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 3, 0)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(8)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 5
      // DeleteEventsCompleted(6) did not equal DeleteSnapshotsCompleted(Criteria(SnapshotSelectionCriteria(4,9223372036854775807,1,0)))
      // because events and snapshots come from different sources so racy,
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 4, 1)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(9)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 5, 2)

      snapshotSignalProbe.expectMessageType[SnapshotCompleted].metadata.sequenceNr should ===(10)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 7
      expectDeleteSnapshotCompleted(snapshotSignalProbe, 6, 3)
    }

  }
}
