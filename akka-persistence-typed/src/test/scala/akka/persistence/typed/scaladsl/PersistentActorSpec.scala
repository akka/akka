/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.typed.scaladsl

import scala.concurrent.duration._
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, StartSupport, SupervisorStrategy, Terminated, TypedSpec }
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import akka.util.Timeout
import akka.persistence.typed.scaladsl.PersistentActor._

object PersistentActorSpec {

  val config = ConfigFactory.parseString("""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)

  sealed trait Command
  final case object Increment extends Command
  final case object IncrementLater extends Command
  final case object IncrementAfterReceiveTimeout extends Command
  final case object IncrementTwiceAndThenLog extends Command
  final case object DoNothingAndThenLog extends Command
  final case object EmptyEventsListAndThenLog extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  private case object Timeout extends Command

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  case object Tick

  val firstLogging = "first logging"
  val secondLogging = "second logging"

  def counter(persistenceId: String)(implicit actorSystem: ActorSystem[TypedSpec.Command], testSettings: TestKitSettings): Behavior[Command] =
    counter(persistenceId, TestProbe[String].ref)

  def counter(persistenceId: String, loggingActor: ActorRef[String]): Behavior[Command] = {

    PersistentActor.immutable[Command, Event, State](
      persistenceId,
      initialState = State(0, Vector.empty),
      commandHandler = CommandHandler[Command, Event, State]((ctx, state, cmd) ⇒ cmd match {
        case Increment ⇒
          Effect.persist(Incremented(1))
        case GetValue(replyTo) ⇒
          replyTo ! state
          Effect.none
        case IncrementLater ⇒
          // purpose is to test signals
          val delay = ctx.spawnAnonymous(Actor.withTimers[Tick.type] { timers ⇒
            timers.startSingleTimer(Tick, Tick, 10.millis)
            Actor.immutable((_, msg) ⇒ msg match {
              case Tick ⇒ Actor.stopped
            })
          })
          ctx.watch(delay)
          Effect.none
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
      })
        .onSignal {
          case (_, _, Terminated(_)) ⇒
            Effect.persist(Incremented(10))
        },
      eventHandler = (state, evt) ⇒ evt match {
        case Incremented(delta) ⇒
          State(state.value + delta, state.history :+ state.value)
      })
  }

}

class PersistentActorSpec extends TypedSpec(PersistentActorSpec.config) with Eventually with StartSupport {
  import PersistentActorSpec._

  implicit val testSettings = TestKitSettings(system)

  "A typed persistent actor" must {

    "persist an event" in {
      val c = start(counter("c1"))

      val probe = TestProbe[State]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(State(1, Vector(0)))
    }

    "replay stored events" in {
      val c = start(counter("c2"))

      val probe = TestProbe[State]
      c ! Increment
      c ! Increment
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(State(3, Vector(0, 1, 2)))

      val c2 = start(counter("c2"))
      c2 ! GetValue(probe.ref)
      probe.expectMsg(State(3, Vector(0, 1, 2)))
      c2 ! Increment
      c2 ! GetValue(probe.ref)
      probe.expectMsg(State(4, Vector(0, 1, 2, 3)))
    }

    "handle Terminated signal" in {
      val c = start(counter("c3"))

      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementLater
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMsg(State(11, Vector(0, 1)))
      }
    }

    "handle receive timeout" in {
      val c = start(counter("c4"))

      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementAfterReceiveTimeout
      // let it timeout
      Thread.sleep(500)
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMsg(State(101, Vector(0, 1)))
      }
    }

    /**
     * Verify that all side-effects callbacks are called (in order) and only once.
     * The [[IncrementTwiceAndThenLog]] command will emit two Increment events
     */
    "chainable side effects with events" in {
      val loggingProbe = TestProbe[String]
      val c = start(counter("c5", loggingProbe.ref))

      val probe = TestProbe[State]

      c ! IncrementTwiceAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMsg(State(2, Vector(0, 1)))

      loggingProbe.expectMsg(firstLogging)
      loggingProbe.expectMsg(secondLogging)
    }

    /** Proves that side-effects are called when emitting an empty list of events */
    "chainable side effects without events" in {
      val loggingProbe = TestProbe[String]
      val c = start(counter("c6", loggingProbe.ref))

      val probe = TestProbe[State]
      c ! EmptyEventsListAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMsg(State(0, Vector.empty))
      loggingProbe.expectMsg(firstLogging)
    }

    /** Proves that side-effects are called when explicitly calling Effect.none */
    "chainable side effects when doing nothing (Effect.none)" in {
      val loggingProbe = TestProbe[String]
      val c = start(counter("c7", loggingProbe.ref))

      val probe = TestProbe[State]
      c ! DoNothingAndThenLog
      c ! GetValue(probe.ref)
      probe.expectMsg(State(0, Vector.empty))
      loggingProbe.expectMsg(firstLogging)
    }

    "work when wrapped in other behavior" in {
      // FIXME This is a major problem with current implementation. Since the
      // behavior is running as an untyped PersistentActor it's not possible to
      // wrap it in Actor.deferred or Actor.supervise
      pending
      val behavior = Actor.supervise[Command](counter("c13"))
        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.1))
      val c = start(behavior)
    }
  }

}
