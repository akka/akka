/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.PoisonPill
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ TypedActorRefOps, TypedActorSystemOps }
import akka.actor.typed.{ ActorRef, Behavior, TypedAkkaSpecWithShutdown }
import akka.persistence.Persistence
import akka.persistence.RecoveryPermitter.{ RecoveryPermitGranted, RequestRecoveryPermit, ReturnRecoveryPermit }
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehaviors }
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object RecoveryPermitterSpec {

  class TestExc extends RuntimeException("simulated exc") with NoStackTrace

  trait State

  object EmptyState extends State

  object EventState extends State

  trait Command

  case object StopActor extends Command

  trait Event

  case object Recovered extends Event

  case object Ping extends Event

  def persistentBehavior(
    name:            String,
    commandProbe:    TestProbe[Any],
    eventProbe:      TestProbe[Any],
    throwOnRecovery: Boolean        = false): Behavior[Command] =
    PersistentBehaviors.immutable[Command, Event, State](
      persistenceId = name,
      initialState = EmptyState,
      commandHandler = CommandHandler.command {
        case StopActor ⇒ Effect.stop
        case command   ⇒ commandProbe.ref ! command; Effect.none
      },
      eventHandler = { (state, event) ⇒ eventProbe.ref ! event; state }
    ).onRecoveryCompleted {
        case (_, _) ⇒
          eventProbe.ref ! Recovered
          if (throwOnRecovery) throw new TestExc
      }

  def forwardingBehavior(target: TestProbe[Any]): Behavior[Any] =
    Behaviors.immutable[Any] {
      (_, any) ⇒ target.ref ! any; Behaviors.same
    }
}

class RecoveryPermitterSpec extends ActorTestKit with TypedAkkaSpecWithShutdown with Eventually {

  import RecoveryPermitterSpec._

  override def config: Config = ConfigFactory.parseString(
    s"""
        akka.persistence.max-concurrent-recoveries = 3
        akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
        akka.actor.warn-about-java-serializer-usage = off
      """)

  private val permitter = Persistence(system.toUntyped).recoveryPermitter

  def requestPermit(p: TestProbe[Any]): Unit = {
    permitter.tell(RequestRecoveryPermit, p.ref.toUntyped)
    p.expectMessage(RecoveryPermitGranted)
  }

  val p1 = TestProbe[Any]()
  val p2 = TestProbe[Any]()
  val p3 = TestProbe[Any]()
  val p4 = TestProbe[Any]()
  val p5 = TestProbe[Any]()

  "RecoveryPermitter" must {
    "grant permits up to the limit" in {
      requestPermit(p1)
      requestPermit(p2)
      requestPermit(p3)

      permitter.tell(RequestRecoveryPermit, p4.ref.toUntyped)
      permitter.tell(RequestRecoveryPermit, p5.ref.toUntyped)
      p4.expectNoMessage(100.millis)
      p5.expectNoMessage(10.millis)

      permitter.tell(ReturnRecoveryPermit, p2.ref.toUntyped)
      p4.expectMessage(RecoveryPermitGranted)
      p5.expectNoMessage(100.millis)

      permitter.tell(ReturnRecoveryPermit, p1.ref.toUntyped)
      p5.expectMessage(RecoveryPermitGranted)

      permitter.tell(ReturnRecoveryPermit, p3.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p4.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p5.ref.toUntyped)
    }

    "grant recovery when all permits not used" in {
      requestPermit(p1)

      spawn(persistentBehavior("p2", p2, p2))
      p2.expectMessage(Recovered)
      permitter.tell(ReturnRecoveryPermit, p1.ref.toUntyped)
    }

    "delay recovery when all permits used" in {
      requestPermit(p1)
      requestPermit(p2)
      requestPermit(p3)

      val persistentActor = spawn(persistentBehavior("p4", p4, p4))
      persistentActor ! StopActor
      p4.expectNoMessage(200.millis)

      permitter.tell(ReturnRecoveryPermit, p3.ref.toUntyped)
      p4.expectMessage(Recovered)
      p4.expectTerminated(persistentActor, 1.second)

      permitter.tell(ReturnRecoveryPermit, p1.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p2.ref.toUntyped)
    }

    "return permit when actor is pre-maturely terminated before holding permit" in {
      requestPermit(p1)
      requestPermit(p2)
      requestPermit(p3)

      val persistentActor = spawn(persistentBehavior("p4", p4, p4))
      p4.expectNoMessage(100.millis)

      permitter.tell(RequestRecoveryPermit, p5.ref.toUntyped)
      p5.expectNoMessage(100.millis)

      // PoisonPill is not stashed
      persistentActor.toUntyped ! PoisonPill

      // persistentActor didn't hold a permit so still
      p5.expectNoMessage(100.millis)

      permitter.tell(ReturnRecoveryPermit, p1.ref.toUntyped)
      p5.expectMessage(RecoveryPermitGranted)

      permitter.tell(ReturnRecoveryPermit, p2.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p3.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p5.ref.toUntyped)
    }

    "return permit when actor is pre-maturely terminated when holding permit" in {
      val actor = spawn(forwardingBehavior(p1))
      permitter.tell(RequestRecoveryPermit, actor.toUntyped)
      p1.expectMessage(RecoveryPermitGranted)

      requestPermit(p2)
      requestPermit(p3)

      permitter.tell(RequestRecoveryPermit, p4.ref.toUntyped)
      p4.expectNoMessage(100.millis)

      actor.toUntyped ! PoisonPill
      p4.expectMessage(RecoveryPermitGranted)

      permitter.tell(ReturnRecoveryPermit, p2.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p3.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p4.ref.toUntyped)
    }

    "return permit when actor throws from RecoveryCompleted" in {
      requestPermit(p1)
      requestPermit(p2)

      val stopProbe = TestProbe[ActorRef[Command]]()
      val parent = spawn(
        Behaviors.setup[Command](ctx ⇒ {
          val persistentActor =
            ctx.spawnAnonymous(persistentBehavior("p3", p3, p3, throwOnRecovery = true))
          ctx.watch(persistentActor)
          Behaviors.immutable[Command] {
            case (_, StopActor) ⇒
              stopProbe.ref ! persistentActor
              ctx.stop(persistentActor)
              Behavior.same
            case (_, message) ⇒
              persistentActor ! message
              Behaviors.same
          }
        })
      )
      p3.expectMessage(Recovered)
      // stop it
      parent ! StopActor
      val persistentActor = stopProbe.expectMessageType[ActorRef[Command]]
      stopProbe.expectTerminated(persistentActor, 1.second)

      requestPermit(p4)

      permitter.tell(ReturnRecoveryPermit, p1.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p2.ref.toUntyped)
      permitter.tell(ReturnRecoveryPermit, p4.ref.toUntyped)
    }
  }
}
