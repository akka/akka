/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Success
import scala.util.Try
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
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
import akka.persistence.typed.SnapshotSelectionCriteria
import akka.serialization.jackson.CborSerializable
import akka.util.unused
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.concurrent.duration._

object EventSourcedBehaviorRetentionSpec extends Matchers {

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

  final case class WrappedSignal(signal: EventSourcedSignal)

  sealed trait Event extends CborSerializable
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int]) extends CborSerializable

  def counter(
      @unused ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: Option[ActorRef[(State, Event)]] = None,
      snapshotSignalProbe: Option[ActorRef[WrappedSignal]] = None,
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
        snapshotSignalProbe.foreach(_ ! WrappedSignal(sc))
      case (_, sf: SnapshotFailed) =>
        snapshotSignalProbe.foreach(_ ! WrappedSignal(sf))
      case (_, dc: DeleteSnapshotsCompleted) =>
        snapshotSignalProbe.foreach(_ ! WrappedSignal(dc))
      case (_, dsf: DeleteSnapshotsFailed) =>
        snapshotSignalProbe.foreach(_ ! WrappedSignal(dsf))
      case (_, e: EventSourcedSignal) =>
        eventSignalProbe.foreach(_ ! Success(e))
    }
  }

  implicit class WrappedSignalProbeAssert(probe: TestProbe[WrappedSignal]) {
    def expectSnapshotCompleted(sequenceNumber: Int): SnapshotCompleted = {
      val wrapped = probe.expectMessageType[WrappedSignal]
      wrapped.signal shouldBe a[SnapshotCompleted]
      val completed = wrapped.signal.asInstanceOf[SnapshotCompleted]
      completed.metadata.sequenceNr should ===(sequenceNumber)
      completed
    }

    def expectDeleteSnapshotCompleted(maxSequenceNr: Long, minSequenceNr: Long): DeleteSnapshotsCompleted = {
      val wrapped = probe.expectMessageType[WrappedSignal]
      wrapped.signal shouldBe a[DeleteSnapshotsCompleted]
      val signal = wrapped.signal.asInstanceOf[DeleteSnapshotsCompleted]
      signal.target should ===(
        DeletionTarget.Criteria(
          SnapshotSelectionCriteria.latest.withMaxSequenceNr(maxSequenceNr).withMinSequenceNr(minSequenceNr)))
      signal
    }
  }

}

class EventSourcedBehaviorRetentionSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorRetentionSpec.conf)
    with WordSpecLike
    with LogCapturing {

  import EventSourcedBehaviorRetentionSpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

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
      val snapshotProbe = createTestProbe[WrappedSignal]()

      val c2 = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, probe = Some(probeC2.ref), snapshotSignalProbe = Some(snapshotProbe.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 2, keepNSnapshots = 2))))
      probeC2.expectMessage[(State, Event)]((State(0, Vector()), Incremented(1)))

      c2 ! Increment
      snapshotProbe.expectSnapshotCompleted(2)
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
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
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
      snapshotSignalProbe.expectSnapshotCompleted(3)
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
      val snapshotSignalProbe = TestProbe[WrappedSignal]
      val alwaysSnapshot: Behavior[Command] =
        Behaviors.setup { ctx =>
          counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref)).snapshotWhen { (_, _, _) =>
            true
          }
        }
      val c = spawn(alwaysSnapshot)
      val replyProbe = TestProbe[State]()

      c ! Increment
      snapshotSignalProbe.expectSnapshotCompleted(1)
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
      val snapshotSignalProbe = TestProbe[WrappedSignal]
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
      snapshotSignalProbe.expectSnapshotCompleted(3)
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

    "delete snapshots automatically, based on criteria" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 3, keepNSnapshots = 2))))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotSignalProbe.expectSnapshotCompleted(3)
      snapshotSignalProbe.expectSnapshotCompleted(6)
      snapshotSignalProbe.expectSnapshotCompleted(9)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(3, 0)

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(20, (0 until 20).toVector))
      snapshotSignalProbe.expectSnapshotCompleted(12)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(6, 0)
      snapshotSignalProbe.expectSnapshotCompleted(15)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(9, 3)
      snapshotSignalProbe.expectSnapshotCompleted(18)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(12, 6)

      snapshotSignalProbe.expectNoMessage()
    }

    "optionally delete both old events and snapshots" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
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
      snapshotSignalProbe.expectSnapshotCompleted(3)
      snapshotSignalProbe.expectSnapshotCompleted(6)
      snapshotSignalProbe.expectSnapshotCompleted(9)

      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 3
      // Note that when triggering deletion of snapshots from deletion of events it is intentionally "off by one".
      // The reason for -1 is that a snapshot at the exact toSequenceNr is still useful and the events
      // after that can be replayed after that snapshot, but replaying the events after toSequenceNr without
      // starting at the snapshot at toSequenceNr would be invalid.
      snapshotSignalProbe.expectDeleteSnapshotCompleted(2, 0)

      // one at a time since snapshotting+event-deletion switches to running state before deleting snapshot so ordering
      // if sending many commands in one go is not deterministic
      persistentActor ! Increment // 11
      persistentActor ! Increment // 12
      snapshotSignalProbe.expectSnapshotCompleted(12)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      snapshotSignalProbe.expectDeleteSnapshotCompleted(5, 0)

      persistentActor ! Increment // 13
      persistentActor ! Increment // 14
      persistentActor ! Increment // 11
      persistentActor ! Increment // 15
      snapshotSignalProbe.expectSnapshotCompleted(15)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 9
      snapshotSignalProbe.expectDeleteSnapshotCompleted(8, 2)

      persistentActor ! Increment // 16
      persistentActor ! Increment // 17
      persistentActor ! Increment // 18
      snapshotSignalProbe.expectSnapshotCompleted(18)

      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 12
      snapshotSignalProbe.expectDeleteSnapshotCompleted(11, 5)

      eventProbe.expectNoMessage()
      snapshotSignalProbe.expectNoMessage()
    }

    "be possible to combine snapshotWhen and retention criteria" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
      val eventProbe = TestProbe[Try[EventSourcedSignal]]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref), eventSignalProbe = Some(eventProbe.ref))
          .snapshotWhen((_, _, seqNr) => seqNr == 3 || seqNr == 13)
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 5, keepNSnapshots = 1))))

      (1 to 3).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, (0 until 3).toVector))
      snapshotSignalProbe.expectSnapshotCompleted(3)
      eventProbe.expectNoMessage()

      (4 to 10).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectSnapshotCompleted(5)
      snapshotSignalProbe.expectSnapshotCompleted(10)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(5, 0)

      (11 to 13).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectSnapshotCompleted(13)
      // no deletes triggered by snapshotWhen
      eventProbe.within(3.seconds) {
        eventProbe.expectNoMessage()
        snapshotSignalProbe.expectNoMessage()
      }

      (14 to 16).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(16, (0 until 16).toVector))
      snapshotSignalProbe.expectSnapshotCompleted(15)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(10, 5)
      eventProbe.within(3.seconds) {
        eventProbe.expectNoMessage()
        snapshotSignalProbe.expectNoMessage()
      }
    }

    "be possible to combine snapshotWhen and retention criteria withDeleteEventsOnSnapshot" in {
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
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
      snapshotSignalProbe.expectSnapshotCompleted(2) // every-2 through criteria
      snapshotSignalProbe.expectSnapshotCompleted(3) // snapshotWhen
      // no event deletes or snapshot deletes after snapshotWhen
      eventProbe.within(3.seconds) {
        eventProbe.expectNoMessage()
        snapshotSignalProbe.expectNoMessage()
      }

      // one at a time since snapshotting+event-deletion switches to running state before deleting snapshot so ordering
      // if sending many commands in one go is not deterministic
      persistentActor ! Increment // 4
      snapshotSignalProbe.expectSnapshotCompleted(4) // every-2 through criteria
      persistentActor ! Increment // 5
      persistentActor ! Increment // 6
      snapshotSignalProbe.expectSnapshotCompleted(6) // every-2 through criteria

      persistentActor ! Increment // 7
      persistentActor ! Increment // 8
      snapshotSignalProbe.expectSnapshotCompleted(8) // every-2 through criteria
      // triggers delete up to snapshot no 2
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 2
      snapshotSignalProbe.expectDeleteSnapshotCompleted(1, 0) // then delete oldest snapshot

      persistentActor ! Increment // 9
      persistentActor ! Increment // 10
      snapshotSignalProbe.expectSnapshotCompleted(10) // every-2 through criteria
      snapshotSignalProbe.expectDeleteSnapshotCompleted(3, 0)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 4

      persistentActor ! Increment // 11
      persistentActor ! Increment // 12
      snapshotSignalProbe.expectSnapshotCompleted(12) // every-2 through criteria
      snapshotSignalProbe.expectDeleteSnapshotCompleted(5, 0)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6

      persistentActor ! Increment // 13
      snapshotSignalProbe.expectSnapshotCompleted(13) // snapshotWhen
      // no deletes triggered by snapshotWhen
      eventProbe.within(3.seconds) {
        eventProbe.expectNoMessage()
        snapshotSignalProbe.expectNoMessage()
      }

      persistentActor ! Increment // 14
      snapshotSignalProbe.expectSnapshotCompleted(14) // every-2 through criteria
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 8
      snapshotSignalProbe.expectDeleteSnapshotCompleted(7, 1)

      persistentActor ! Increment // 15
      persistentActor ! Increment // 16
      snapshotSignalProbe.expectSnapshotCompleted(16) // every-2 through criteria
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 10
      snapshotSignalProbe.expectDeleteSnapshotCompleted(9, 3)

      eventProbe.within(3.seconds) {
        eventProbe.expectNoMessage()
        snapshotSignalProbe.expectNoMessage()
      }
    }

    "be possible to snapshot every event" in {
      // very bad idea to snapshot every event, but technically possible
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
      val replyProbe = TestProbe[State]()

      val persistentActor = spawn(
        Behaviors.setup[Command](ctx =>
          counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref))
            .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 1, keepNSnapshots = 3))))

      (1 to 10).foreach(_ => persistentActor ! Increment)
      persistentActor ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(10, (0 until 10).toVector))
      snapshotSignalProbe.expectSnapshotCompleted(1)
      snapshotSignalProbe.expectSnapshotCompleted(2)
      snapshotSignalProbe.expectSnapshotCompleted(3)
      snapshotSignalProbe.expectSnapshotCompleted(4)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(1, 0)

      snapshotSignalProbe.expectSnapshotCompleted(5)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(2, 0)

      snapshotSignalProbe.expectSnapshotCompleted(6)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(3, 0)

      snapshotSignalProbe.expectSnapshotCompleted(7)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(4, 1)

      snapshotSignalProbe.expectSnapshotCompleted(8)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(5, 2)

      snapshotSignalProbe.expectSnapshotCompleted(9)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(6, 3)

      snapshotSignalProbe.expectSnapshotCompleted(10)
      snapshotSignalProbe.expectDeleteSnapshotCompleted(7, 4)
    }

    "be possible to snapshot every event withDeleteEventsOnSnapshot" in {
      // very bad idea to snapshot every event, but technically possible
      val pid = nextPid()
      val snapshotSignalProbe = TestProbe[WrappedSignal]()
      val eventProbe = TestProbe[Try[EventSourcedSignal]]()

      val persistentActor = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid, snapshotSignalProbe = Some(snapshotSignalProbe.ref), eventSignalProbe = Some(eventProbe.ref))
          .withRetention(
            RetentionCriteria.snapshotEvery(numberOfEvents = 1, keepNSnapshots = 3).withDeleteEventsOnSnapshot)))

      // one at a time since snapshotting+event-deletion switches to running state before deleting snapshot so ordering
      // if sending many commands in one go is not deterministic
      (1 to 4).foreach(_ => persistentActor ! Increment)
      snapshotSignalProbe.expectSnapshotCompleted(1)
      snapshotSignalProbe.expectSnapshotCompleted(2)
      snapshotSignalProbe.expectSnapshotCompleted(3)
      snapshotSignalProbe.expectSnapshotCompleted(4)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 1

      persistentActor ! Increment // 5
      snapshotSignalProbe.expectSnapshotCompleted(5)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 2
      snapshotSignalProbe.expectDeleteSnapshotCompleted(1, 0)

      persistentActor ! Increment // 6
      snapshotSignalProbe.expectSnapshotCompleted(6)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 3
      snapshotSignalProbe.expectDeleteSnapshotCompleted(2, 0)

      persistentActor ! Increment // 7
      snapshotSignalProbe.expectSnapshotCompleted(7)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 4
      snapshotSignalProbe.expectDeleteSnapshotCompleted(3, 0)

      persistentActor ! Increment // 8
      snapshotSignalProbe.expectSnapshotCompleted(8)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 5
      snapshotSignalProbe.expectDeleteSnapshotCompleted(4, 1)

      persistentActor ! Increment // 9
      snapshotSignalProbe.expectSnapshotCompleted(9)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 6
      snapshotSignalProbe.expectDeleteSnapshotCompleted(5, 2)

      persistentActor ! Increment // 10
      snapshotSignalProbe.expectSnapshotCompleted(10)
      eventProbe.expectMessageType[Success[DeleteEventsCompleted]].value.toSequenceNr shouldEqual 7
      snapshotSignalProbe.expectDeleteSnapshotCompleted(6, 3)
    }

  }
}
