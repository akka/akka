/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.persistence.scaladsl

import scala.concurrent.duration._

import akka.typed.ActorRef
import akka.typed.ActorSystem
import akka.typed.Behavior
import akka.typed.TypedSpec
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl._
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import akka.util.Timeout
import akka.typed.persistence.scaladsl.PersistentActor._
import akka.typed.SupervisorStrategy
import akka.typed.Terminated

object PersistentActorSpec {

  val config = ConfigFactory.parseString("""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)

  sealed trait Command
  final case object Increment extends Command
  final case object IncrementLater extends Command
  final case object IncrementAfterReceiveTimeout extends Command
  final case class GetValue(replyTo: ActorRef[State]) extends Command
  private case object Timeout extends Command

  sealed trait Event
  final case class Incremented(delta: Int) extends Event

  final case class State(value: Int, history: Vector[Int])

  case object Tick

  def counter(persistenceId: String): Behavior[Command] = {
    PersistentActor.immutable[Command, Event, State](
      persistenceId,
      initialState = State(0, Vector.empty),
      actions = Actions[Command, Event, State]((ctx, cmd, state) ⇒ cmd match {
        case Increment ⇒
          Persist(Incremented(1))
        case GetValue(replyTo) ⇒
          replyTo ! state
          PersistNothing()
        case IncrementLater ⇒
          // purpose is to test signals
          val delay = ctx.spawnAnonymous(Actor.withTimers[Tick.type] { timers ⇒
            timers.startSingleTimer(Tick, Tick, 10.millis)
            Actor.immutable((_, msg) ⇒ msg match {
              case Tick ⇒ Actor.stopped
            })
          })
          ctx.watch(delay)
          PersistNothing()
        case IncrementAfterReceiveTimeout ⇒
          ctx.setReceiveTimeout(10.millis, Timeout)
          PersistNothing()
        case Timeout ⇒
          ctx.cancelReceiveTimeout()
          Persist(Incremented(100))
      })
        .onSignal {
          case (_, Terminated(_), _) ⇒
            Persist(Incremented(10))
        },
      applyEvent = (evt, state) ⇒ evt match {
        case Incremented(delta) ⇒
          State(state.value + delta, state.history :+ state.value)
      })
  }

}

class PersistentActorSpec extends TypedSpec(PersistentActorSpec.config) with Eventually {
  import PersistentActorSpec._

  trait RealTests extends StartSupport {
    implicit def system: ActorSystem[TypedSpec.Command]
    implicit val testSettings = TestKitSettings(system)

    def `persist an event`(): Unit = {
      val c = start(counter("c1"))

      val probe = TestProbe[State]
      c ! Increment
      c ! GetValue(probe.ref)
      probe.expectMsg(State(1, Vector(0)))
    }

    def `replay stored events`(): Unit = {
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

    def `handle Terminated signal`(): Unit = {
      val c = start(counter("c3"))

      val probe = TestProbe[State]
      c ! Increment
      c ! IncrementLater
      eventually {
        c ! GetValue(probe.ref)
        probe.expectMsg(State(11, Vector(0, 1)))
      }
    }

    def `handle receive timeout`(): Unit = {
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

    def `work when wrapped in other behavior`(): Unit = {
      // FIXME This is a major problem with current implementation. Since the
      // behavior is running as an untyped PersistentActor it's not possible to
      // wrap it in Actor.deferred or Actor.supervise
      pending
      val behavior = Actor.supervise[Command](counter("c13"))
        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.1))
      val c = start(behavior)
    }

  }

  object `A PersistentActor (real, adapted)` extends RealTests with AdaptedSystem
}
