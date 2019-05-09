/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.Done
import akka.actor.ActorInitializationException
import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.SelectedSnapshot
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.Sequence
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.SnapshotMetadata
import akka.persistence.{ SnapshotMetadata => UntypedSnapshotMetadata }
import akka.persistence.{ SnapshotSelectionCriteria => UntypedSnapshotSelectionCriteria }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedBehaviorSpec {

  //#event-wrapper
  case class Wrapper[T](t: T)
  class WrapperEventAdapter[T] extends EventAdapter[T, Wrapper[T]] {
    override def toJournal(e: T): Wrapper[T] = Wrapper(e)
    override def fromJournal(p: Wrapper[T]): T = p.t
  }
  //#event-wrapper

  class SlowInMemorySnapshotStore extends SnapshotStore {

    private var state = Map.empty[String, (Any, UntypedSnapshotMetadata)]

    override def loadAsync(
        persistenceId: String,
        criteria: UntypedSnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
      Promise().future // never completed
    }

    override def saveAsync(metadata: UntypedSnapshotMetadata, snapshot: Any): Future[Unit] = {
      state = state.updated(metadata.persistenceId, (snapshot, metadata))
      Future.successful(())
    }

    override def deleteAsync(metadata: UntypedSnapshotMetadata): Future[Unit] = {
      state = state.filterNot { case (k, (_, b)) => k == metadata.persistenceId && b.sequenceNr == metadata.sequenceNr }
      Future.successful(())
    }

    override def deleteAsync(persistenceId: String, criteria: UntypedSnapshotSelectionCriteria): Future[Unit] = {
      val range = criteria.minSequenceNr to criteria.maxSequenceNr
      state = state.filterNot { case (k, (_, b)) => k == persistenceId && range.contains(b.sequenceNr) }
      Future.successful(())
    }
  }

  // also used from PersistentActorTest
  def conf: Config = ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.loggers = [akka.testkit.TestEventListener]
    # akka.persistence.typed.log-stashing = on
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"

    slow-snapshot-store.class = "${classOf[SlowInMemorySnapshotStore].getName}"
    short-recovery-timeout {
      class = "${classOf[InmemJournal].getName}"
      recovery-event-timeout = 10 millis
    }
    """)

  sealed trait Command
  final case object Increment extends Command
  final case object IncrementThenLogThenStop extends Command
  final case object IncrementTwiceThenLogThenStop extends Command
  final case class IncrementWithPersistAll(nr: Int) extends Command
  final case object IncrementLater extends Command
  final case object IncrementAfterReceiveTimeout extends Command
  final case object IncrementTwiceAndThenLog extends Command
  final case class IncrementWithConfirmation(override val replyTo: ActorRef[Done])
      extends Command
      with ExpectingReply[Done]
  final case object DoNothingAndThenLog extends Command
  final case object EmptyEventsListAndThenLog extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  final case object DelayFinished extends Command
  private case object Timeout extends Command
  final case object LogThenStop extends Command
  final case object Fail extends Command
  final case object StopIt extends Command

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  case object Tick

  val firstLogging = "first logging"
  val secondLogging = "second logging"

  def counter(persistenceId: PersistenceId)(implicit system: ActorSystem[_]): Behavior[Command] =
    Behaviors.setup(ctx => counter(ctx, persistenceId))

  def counter(persistenceId: PersistenceId, logging: ActorRef[String])(
      implicit system: ActorSystem[_]): Behavior[Command] =
    Behaviors.setup(ctx => counter(ctx, persistenceId, logging))

  def counter(ctx: ActorContext[Command], persistenceId: PersistenceId)(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      loggingActor = TestProbe[String].ref,
      probe = TestProbe[(State, Event)].ref,
      snapshotProbe = TestProbe[Try[SnapshotMetadata]].ref)

  def counter(ctx: ActorContext[Command], persistenceId: PersistenceId, logging: ActorRef[String])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(
      ctx,
      persistenceId,
      loggingActor = logging,
      probe = TestProbe[(State, Event)].ref,
      TestProbe[Try[SnapshotMetadata]].ref)

  def counterWithProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: ActorRef[(State, Event)],
      snapshotProbe: ActorRef[Try[SnapshotMetadata]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, TestProbe[String].ref, probe, snapshotProbe)

  def counterWithProbe(ctx: ActorContext[Command], persistenceId: PersistenceId, probe: ActorRef[(State, Event)])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, TestProbe[String].ref, probe, TestProbe[Try[SnapshotMetadata]].ref)

  def counterWithSnapshotProbe(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      probe: ActorRef[Try[SnapshotMetadata]])(
      implicit system: ActorSystem[_]): EventSourcedBehavior[Command, Event, State] =
    counter(ctx, persistenceId, TestProbe[String].ref, TestProbe[(State, Event)].ref, snapshotProbe = probe)

  def counter(
      ctx: ActorContext[Command],
      persistenceId: PersistenceId,
      loggingActor: ActorRef[String],
      probe: ActorRef[(State, Event)],
      snapshotProbe: ActorRef[Try[SnapshotMetadata]]): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId,
      emptyState = State(0, Vector.empty),
      commandHandler = (state, cmd) =>
        cmd match {
          case Increment =>
            Effect.persist(Incremented(1))

          case IncrementThenLogThenStop =>
            Effect
              .persist(Incremented(1))
              .thenRun { (_: State) =>
                loggingActor ! firstLogging
              }
              .thenStop

          case IncrementTwiceThenLogThenStop =>
            Effect
              .persist(Incremented(1), Incremented(2))
              .thenRun { (_: State) =>
                loggingActor ! firstLogging
              }
              .thenStop

          case IncrementWithPersistAll(n) =>
            Effect.persist((0 until n).map(_ => Incremented(1)))

          case cmd: IncrementWithConfirmation =>
            Effect.persist(Incremented(1)).thenReply(cmd)(_ => Done)

          case GetValue(replyTo) =>
            replyTo ! state
            Effect.none

          case IncrementLater =>
            // purpose is to test signals
            val delay = ctx.spawnAnonymous(Behaviors.withTimers[Tick.type] { timers =>
              timers.startSingleTimer(Tick, Tick, 10.millis)
              Behaviors.receive((_, msg) =>
                msg match {
                  case Tick => Behaviors.stopped
                })
            })
            ctx.watchWith(delay, DelayFinished)
            Effect.none

          case DelayFinished =>
            Effect.persist(Incremented(10))

          case IncrementAfterReceiveTimeout =>
            ctx.setReceiveTimeout(10.millis, Timeout)
            Effect.none

          case Timeout =>
            ctx.cancelReceiveTimeout()
            Effect.persist(Incremented(100))

          case IncrementTwiceAndThenLog =>
            Effect
              .persist(Incremented(1), Incremented(1))
              .thenRun { (_: State) =>
                loggingActor ! firstLogging
              }
              .thenRun { _ =>
                loggingActor ! secondLogging
              }

          case EmptyEventsListAndThenLog =>
            Effect
              .persist(List.empty) // send empty list of events
              .thenRun { _ =>
                loggingActor ! firstLogging
              }

          case DoNothingAndThenLog =>
            Effect.none.thenRun { _ =>
              loggingActor ! firstLogging
            }

          case LogThenStop =>
            Effect
              .none[Event, State]
              .thenRun { _ =>
                loggingActor ! firstLogging
              }
              .thenStop

          case Fail =>
            throw new TestException("boom!")

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
    }
  }
}

class EventSourcedBehaviorSpec extends ScalaTestWithActorTestKit(EventSourcedBehaviorSpec.conf) with WordSpecLike {

  import EventSourcedBehaviorSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val materializer = ActorMaterializer()(system.toUntyped)
  val queries: LeveldbReadJournal =
    PersistenceQuery(system.toUntyped).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  // needed for the untyped event filter
  implicit val actorSystem = system.toUntyped

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  actorSystem.eventStream.publish(Mute(EventFilter.info(pattern = ".*was not delivered.*", occurrences = 100)))
  actorSystem.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*", occurrences = 100)))

  "A typed persistent actor" must {

    "persist an event" in {
      val c = spawn(counter(nextPid))
      val probe = TestProbe[State]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }

    "replay stored events" in {
      val pid = nextPid
      val c = spawn(counter(pid))

      val probe = TestProbe[State]
      c ! Increment
      c ! Increment
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(10.seconds, State(3, Vector(0, 1, 2)))

      val c2 = spawn(counter(pid))
      c2 ! GetValue(probe.ref)
      probe.expectMessage(State(3, Vector(0, 1, 2)))
      c2 ! Increment
      c2 ! GetValue(probe.ref)
      probe.expectMessage(State(4, Vector(0, 1, 2, 3)))
    }

    "handle Terminated signal" in {
      val c = spawn(counter(nextPid))
      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementLater
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(11, Vector(0, 1)))
      }
    }

    "handle receive timeout" in {
      val c = spawn(counter(nextPid))

      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementAfterReceiveTimeout
      // let it timeout
      Thread.sleep(500)
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(101, Vector(0, 1)))
      }
    }

    /**
     * Verify that all side-effects callbacks are called (in order) and only once.
     * The [[IncrementTwiceAndThenLog]] command will emit two Increment events
     */
    "chainable side effects with events" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))

      val probe = TestProbe[State]

      c ! IncrementTwiceAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(2, Vector(0, 1)))

      loggingProbe.expectMessage(firstLogging)
      loggingProbe.expectMessage(secondLogging)
    }

    "persist then stop" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))
      val watchProbe = watcher(c)

      c ! IncrementThenLogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")
    }

    "persist(All) then stop" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))
      val watchProbe = watcher(c)

      c ! IncrementTwiceThenLogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")

    }

    "persist an event thenReply" in {
      val c = spawn(counter(nextPid))
      val probe = TestProbe[Done]
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)

      c ! IncrementWithConfirmation(probe.ref)
      c ! IncrementWithConfirmation(probe.ref)
      probe.expectMessage(Done)
      probe.expectMessage(Done)
    }

    /** Proves that side-effects are called when emitting an empty list of events */
    "chainable side effects without events" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))

      val probe = TestProbe[State]
      c ! EmptyEventsListAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(0, Vector.empty))
      loggingProbe.expectMessage(firstLogging)
    }

    /** Proves that side-effects are called when explicitly calling Effect.none */
    "chainable side effects when doing nothing (Effect.none)" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter(nextPid, loggingProbe.ref))

      val probe = TestProbe[State]
      c ! DoNothingAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(0, Vector.empty))
      loggingProbe.expectMessage(firstLogging)
    }

    "work when wrapped in other behavior" in {
      val probe = TestProbe[State]
      val behavior = Behaviors
        .supervise[Command](counter(nextPid))
        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.1))
      val c = spawn(behavior)
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }

    "stop after logging (no persisting)" in {
      val loggingProbe = TestProbe[String]
      val c: ActorRef[Command] = spawn(counter(nextPid, loggingProbe.ref))
      val watchProbe = watcher(c)
      c ! LogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")
    }

    "wrap persistent behavior in tap" in {
      val probe = TestProbe[Command]
      val wrapped: Behavior[Command] = Behaviors.monitor(probe.ref, counter(nextPid))
      val c = spawn(wrapped)

      c ! Increment
      val replyProbe = TestProbe[State]()
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      probe.expectMessage(Increment)
    }

    "tag events" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).withTagger(_ => Set("tag1", "tag2"))))
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByTag("tag1").runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Incremented(1)))
    }

    "adapt events" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command] { ctx =>
        val persistentBehavior = counter(ctx, pid)

        //#install-event-adapter
        persistentBehavior.eventAdapter(new WrapperEventAdapter[Event])
      //#install-event-adapter
      })
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))))

      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

    }

    "adapter multiple events with persist all" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(2)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(
        EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))),
        EventEnvelope(Sequence(2), pid.id, 2, Wrapper(Incremented(1))))

      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "adapt and tag events" in {
      val pid = nextPid
      val c = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid).withTagger(_ => Set("tag99")).eventAdapter(new WrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))))

      val c2 = spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new WrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val taggedEvents = queries.currentEventsByTag("tag99").runWith(Sink.seq).futureValue
      taggedEvents shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, Wrapper(Incremented(1))))
    }

    "handle scheduled message arriving before recovery completed " in {
      val c = spawn(Behaviors.withTimers[Command] { timers =>
        timers.startSingleTimer("tick", Increment, 1.millis)
        Thread.sleep(30) // now it's probably already in the mailbox, and will be stashed
        counter(nextPid)
      })

      val probe = TestProbe[State]
      c ! Increment
      probe.awaitAssert {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(2, Vector(0, 1)))
      }
    }

    "handle scheduled message arriving after recovery completed " in {
      val c = spawn(Behaviors.withTimers[Command] { timers =>
        // probably arrives after recovery completed
        timers.startSingleTimer("tick", Increment, 200.millis)
        counter(nextPid)
      })

      val probe = TestProbe[State]
      c ! Increment
      probe.awaitAssert {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(2, Vector(0, 1)))
      }
    }

    "fail after recovery timeout" in {
      EventFilter.error(start = "Persistence failure when replaying snapshot", occurrences = 1).intercept {
        val c = spawn(
          Behaviors.setup[Command](ctx =>
            counter(ctx, nextPid)
              .withSnapshotPluginId("slow-snapshot-store")
              .withJournalPluginId("short-recovery-timeout")))

        val probe = TestProbe[State]

        probe.expectTerminated(c, probe.remainingOrDefault)
      }
    }

    "not wrap a failure caused by command stashed while recovering in a journal failure" in {
      val pid = nextPid()
      val probe = TestProbe[AnyRef]

      // put some events in there, so that recovering takes a little time
      val c = spawn(Behaviors.setup[Command](counter(_, pid)))
      (0 to 50).foreach { _ =>
        c ! IncrementWithConfirmation(probe.ref)
        probe.expectMessage(Done)
      }
      c ! StopIt
      probe.expectTerminated(c)

      EventFilter[TestException](occurrences = 1).intercept {
        val c2 = spawn(Behaviors.setup[Command](counter(_, pid)))
        c2 ! Fail
        probe.expectTerminated(c2) // should fail
      }
    }

    "fail fast if persistenceId is null" in {
      intercept[IllegalArgumentException] {
        PersistenceId(null)
      }
      val probe = TestProbe[AnyRef]
      EventFilter[ActorInitializationException](start = "persistenceId must not be null", occurrences = 1).intercept {
        val ref = spawn(Behaviors.setup[Command](counter(_, persistenceId = PersistenceId(null))))
        probe.expectTerminated(ref)
      }
      EventFilter[ActorInitializationException](start = "persistenceId must not be null", occurrences = 1).intercept {
        val ref = spawn(Behaviors.setup[Command](counter(_, persistenceId = null)))
        probe.expectTerminated(ref)
      }
    }

    "fail fast if persistenceId is empty" in {
      intercept[IllegalArgumentException] {
        PersistenceId("")
      }
      val probe = TestProbe[AnyRef]
      EventFilter[ActorInitializationException](start = "persistenceId must not be empty", occurrences = 1).intercept {
        val ref = spawn(Behaviors.setup[Command](counter(_, persistenceId = PersistenceId(""))))
        probe.expectTerminated(ref)
      }
    }

    "fail fast if default journal plugin is not defined" in {
      // new ActorSystem without persistence config
      val testkit2 = ActorTestKit(
        ActorTestKitBase.testNameFromCallStack(),
        ConfigFactory.parseString("""
          akka.loggers = [akka.testkit.TestEventListener]
          """))
      try {
        EventFilter[ActorInitializationException](start = "Default journal plugin is not configured", occurrences = 1)
          .intercept {
            val ref = testkit2.spawn(Behaviors.setup[Command](counter(_, nextPid())))
            val probe = testkit2.createTestProbe()
            probe.expectTerminated(ref)
          }(testkit2.system.toUntyped)
      } finally {
        testkit2.shutdownTestKit()
      }
    }

    "fail fast if given journal plugin is not defined" in {
      // new ActorSystem without persistence config
      val testkit2 = ActorTestKit(
        ActorTestKitBase.testNameFromCallStack(),
        ConfigFactory.parseString("""
          akka.loggers = [akka.testkit.TestEventListener]
          """))
      try {
        EventFilter[ActorInitializationException](
          start = "Journal plugin [missing] configuration doesn't exist",
          occurrences = 1).intercept {
          val ref = testkit2.spawn(Behaviors.setup[Command](counter(_, nextPid()).withJournalPluginId("missing")))
          val probe = testkit2.createTestProbe()
          probe.expectTerminated(ref)
        }(testkit2.system.toUntyped)
      } finally {
        testkit2.shutdownTestKit()
      }
    }

    "warn if default snapshot plugin is not defined" in {
      // new ActorSystem without snapshot plugin config
      val testkit2 = ActorTestKit(
        ActorTestKitBase.testNameFromCallStack(),
        ConfigFactory.parseString(s"""
          akka.loggers = [akka.testkit.TestEventListener]
          akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
          akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
          """))
      try {
        EventFilter
          .warning(start = "No default snapshot store configured", occurrences = 1)
          .intercept {
            val ref = testkit2.spawn(Behaviors.setup[Command](counter(_, nextPid())))
            val probe = testkit2.createTestProbe[State]()
            // verify that it's not terminated
            ref ! GetValue(probe.ref)
            probe.expectMessage(State(0, Vector.empty))
          }(testkit2.system.toUntyped)
      } finally {
        testkit2.shutdownTestKit()
      }
    }

    "fail fast if given snapshot plugin is not defined" in {
      // new ActorSystem without snapshot plugin config
      val testkit2 = ActorTestKit(
        ActorTestKitBase.testNameFromCallStack(),
        ConfigFactory.parseString(s"""
          akka.loggers = [akka.testkit.TestEventListener]
          akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
          akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
          """))
      try {
        EventFilter[ActorInitializationException](
          start = "Snapshot store plugin [missing] configuration doesn't exist",
          occurrences = 1).intercept {
          val ref = testkit2.spawn(Behaviors.setup[Command](counter(_, nextPid()).withSnapshotPluginId("missing")))
          val probe = testkit2.createTestProbe()
          probe.expectTerminated(ref)
        }(testkit2.system.toUntyped)
      } finally {
        testkit2.shutdownTestKit()
      }
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
