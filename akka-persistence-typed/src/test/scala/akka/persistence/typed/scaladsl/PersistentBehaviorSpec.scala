/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, SupervisorStrategy, Terminated, TypedAkkaSpecWithShutdown }
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{ SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._

object PersistentBehaviorSpec {

  class InMemorySnapshotStore extends SnapshotStore {

    private var state = Map.empty[String, (Any, SnapshotMetadata)]

    def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
      Future.successful(state.get(persistenceId).map(r ⇒ SelectedSnapshot(r._2, r._1)))
    }

    def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
      state = state.updated(metadata.persistenceId, (snapshot, metadata))
      Future.successful(())
    }

    def deleteAsync(metadata: SnapshotMetadata) = ???
    def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria) = ???
  }

  // also used from PersistentActorTest
  val conf: Config = ConfigFactory.parseString(
    s"""
    akka.loglevel = INFO
    # akka.persistence.typed.log-stashing = INFO

    akka.persistence.snapshot-store.inmem.class = "akka.persistence.typed.scaladsl.PersistentBehaviorSpec$$InMemorySnapshotStore"
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.inmem"

    """)

  sealed trait Command
  final case object Increment extends Command
  final case object IncrementThenLogThenStop extends Command
  final case object IncrementTwiceThenLogThenStop extends Command
  final case class IncrementWithPersistAll(nr: Int) extends Command
  final case object IncrementLater extends Command
  final case object IncrementAfterReceiveTimeout extends Command
  final case object IncrementTwiceAndThenLog extends Command
  final case object DoNothingAndThenLog extends Command
  final case object EmptyEventsListAndThenLog extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  final case object DelayFinished extends Command
  private case object Timeout extends Command
  final case object LogThenStop extends Command

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  case object Tick

  val firstLogging = "first logging"
  val secondLogging = "second logging"

  def counter(persistenceId: String)(implicit system: ActorSystem[_]): PersistentBehavior[Command, Event, State] =
    counter(persistenceId, loggingActor = TestProbe[String].ref, probe = TestProbe[(State, Event)].ref)

  def counter(persistenceId: String, logging: ActorRef[String])(implicit system: ActorSystem[_]): PersistentBehavior[Command, Event, State] =
    counter(persistenceId, loggingActor = logging, probe = TestProbe[(State, Event)].ref)

  def counterWithProbe(persistenceId: String, probe: ActorRef[(State, Event)])(implicit system: ActorSystem[_]): PersistentBehavior[Command, Event, State] =
    counter(persistenceId, TestProbe[String].ref, probe)

  def counter(
    persistenceId: String,
    loggingActor:  ActorRef[String],
    probe:         ActorRef[(State, Event)]): PersistentBehavior[Command, Event, State] = {
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId,
      initialState = State(0, Vector.empty),
      commandHandler = (ctx, state, cmd) ⇒ cmd match {
        case Increment ⇒
          Effect.persist(Incremented(1))

        case IncrementThenLogThenStop ⇒
          Effect.persist(Incremented(1))
            .andThen {
              loggingActor ! firstLogging
            }
            .andThenStop

        case IncrementTwiceThenLogThenStop ⇒
          Effect.persist(Incremented(1), Incremented(2))
            .andThen {
              loggingActor ! firstLogging
            }
            .andThenStop

        case IncrementWithPersistAll(n) ⇒
          Effect.persist((0 until n).map(_ ⇒ Incremented(1)))

        case GetValue(replyTo) ⇒
          replyTo ! state
          Effect.none

        case IncrementLater ⇒
          // purpose is to test signals
          val delay = ctx.spawnAnonymous(Behaviors.withTimers[Tick.type] { timers ⇒
            timers.startSingleTimer(Tick, Tick, 10.millis)
            Behaviors.receive((_, msg) ⇒ msg match {
              case Tick ⇒ Behaviors.stopped
            })
          })
          ctx.watchWith(delay, DelayFinished)
          Effect.none

        case DelayFinished ⇒
          Effect.persist(Incremented(10))

        case IncrementAfterReceiveTimeout ⇒
          ctx.setReceiveTimeout(10.millis, Timeout)
          Effect.none

        case Timeout ⇒
          ctx.cancelReceiveTimeout()
          Effect.persist(Incremented(100))

        case IncrementTwiceAndThenLog ⇒
          Effect
            .persist(Incremented(1), Incremented(1))
            .andThen {
              loggingActor ! firstLogging
            }
            .andThen {
              loggingActor ! secondLogging
            }

        case EmptyEventsListAndThenLog ⇒
          Effect
            .persist(List.empty) // send empty list of events
            .andThen {
              loggingActor ! firstLogging
            }

        case DoNothingAndThenLog ⇒
          Effect
            .none
            .andThen {
              loggingActor ! firstLogging
            }

        case LogThenStop ⇒
          Effect.none
            .andThen {
              loggingActor ! firstLogging
            }
            .andThenStop
      },
      eventHandler = (state, evt) ⇒ evt match {
        case Incremented(delta) ⇒
          probe ! ((state, evt))
          State(state.value + delta, state.history :+ state.value)
      })
  }

}

class PersistentBehaviorSpec extends ActorTestKit with TypedAkkaSpecWithShutdown with Eventually {
  import PersistentBehaviorSpec._

  override def config: Config = PersistentBehaviorSpec.conf

  implicit val testSettings = TestKitSettings(system)

  "A typed persistent actor" must {

    "persist an event" in {
      val c = spawn(counter("c1"))

      val probe = TestProbe[State]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }

    "replay stored events" in {
      val c = spawn(counter("c2"))

      val probe = TestProbe[State]
      c ! Increment
      c ! Increment
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(10.seconds, State(3, Vector(0, 1, 2)))

      val c2 = spawn(counter("c2"))
      c2 ! GetValue(probe.ref)
      probe.expectMessage(State(3, Vector(0, 1, 2)))
      c2 ! Increment
      c2 ! GetValue(probe.ref)
      probe.expectMessage(State(4, Vector(0, 1, 2, 3)))
    }

    "handle Terminated signal" in {
      val c = spawn(counter("c3"))
      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementLater
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMessage(State(11, Vector(0, 1)))
      }
    }

    "handle receive timeout" in {
      val c = spawn(counter("c4"))

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
      val c = spawn(counter("c5", loggingProbe.ref))

      val probe = TestProbe[State]

      c ! IncrementTwiceAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(2, Vector(0, 1)))

      loggingProbe.expectMessage(firstLogging)
      loggingProbe.expectMessage(secondLogging)
    }

    "persist then stop" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter("c5a", loggingProbe.ref))
      val watchProbe = watcher(c)

      c ! IncrementThenLogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")
    }

    "persist(All) then stop" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter("c5b", loggingProbe.ref))
      val watchProbe = watcher(c)

      c ! IncrementTwiceThenLogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")

    }

    /** Proves that side-effects are called when emitting an empty list of events */
    "chainable side effects without events" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter("c6", loggingProbe.ref))

      val probe = TestProbe[State]
      c ! EmptyEventsListAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(0, Vector.empty))
      loggingProbe.expectMessage(firstLogging)
    }

    /** Proves that side-effects are called when explicitly calling Effect.none */
    "chainable side effects when doing nothing (Effect.none)" in {
      val loggingProbe = TestProbe[String]
      val c = spawn(counter("c7", loggingProbe.ref))

      val probe = TestProbe[State]
      c ! DoNothingAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMessage(State(0, Vector.empty))
      loggingProbe.expectMessage(firstLogging)
    }

    "work when wrapped in other behavior" in {
      val probe = TestProbe[State]
      val behavior = Behaviors.supervise[Command](counter("c13"))
        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.1))
      val c = spawn(behavior)
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }

    "stop after logging (no persisting)" in {
      val loggingProbe = TestProbe[String]
      val c: ActorRef[Command] = spawn(counter("c8", loggingProbe.ref))
      val watchProbe = watcher(c)
      c ! LogThenStop
      loggingProbe.expectMessage(firstLogging)
      watchProbe.expectMessage("Terminated")
    }

    "snapshot via predicate" in {
      val alwaysSnapshot: Behavior[Command] =
        Behaviors.setup { context ⇒
          counter("c9").snapshotWhen { (_, _, _) ⇒ true }
        }

      val c = spawn(alwaysSnapshot)
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      val probe = TestProbe[(State, Event)]()
      val c2 = spawn(counterWithProbe("c9", probe.ref))
      // state should be rebuilt from snapshot, no events replayed
      probe.expectNoMessage()
      c2 ! Increment
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "check all events for snapshot in PersistAll" in {
      val snapshotAtTwo = counter("c11").snapshotWhen { (s, _, _) ⇒ s.value == 2 }
      val c: ActorRef[Command] = spawn(snapshotAtTwo)
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(counterWithProbe("c11", probeC2.ref))
      // middle event triggered all to be snapshot
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    "snapshot every N sequence nrs" in {
      val c = spawn(counter("c10").snapshotEvery(2))
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      // no snapshot should have happened
      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(counterWithProbe("c10", probeC2.ref).snapshotEvery(2))
      probeC2.expectMessage[(State, Event)]((State(0, Vector()), Incremented(1)))
      val watchProbeC2 = watcher(c2)
      c2 ! Increment
      c2 ! LogThenStop
      watchProbeC2.expectMessage("Terminated")

      val probeC3 = TestProbe[(State, Event)]()
      val c3 = spawn(counterWithProbe("c10", probeC3.ref).snapshotEvery(2))
      // this time it should have been snapshotted so no events to replay
      probeC3.expectNoMessage()
      c3 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "snapshot every N sequence nrs when persisting multiple events" in {
      val c = spawn(counter("c12").snapshotEvery(2))
      val watchProbe = watcher(c)
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(3)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
      c ! LogThenStop
      watchProbe.expectMessage("Terminated")

      val probeC2 = TestProbe[(State, Event)]()
      val c2 = spawn(counterWithProbe("c12", probeC2.ref).snapshotEvery(2))
      probeC2.expectNoMessage()
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(3, Vector(0, 1, 2)))
    }

    def watcher(toWatch: ActorRef[_]): TestProbe[String] = {
      val probe = TestProbe[String]()
      val w = Behaviors.setup[Any] { (ctx) ⇒
        ctx.watch(toWatch)
        Behaviors.receive[Any] { (_, _) ⇒ Behaviors.same }
          .receiveSignal {
            case (_, s: Terminated) ⇒
              probe.ref ! "Terminated"
              Behaviors.stopped
          }
      }
      spawn(w)
      probe
    }
  }
}
