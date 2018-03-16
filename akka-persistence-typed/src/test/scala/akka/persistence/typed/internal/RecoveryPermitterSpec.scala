package akka.persistence.typed.internal

import akka.actor.typed.{Behavior, TypedAkkaSpecWithShutdown}
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import akka.persistence.typed.scaladsl.{Effect, PersistentBehaviors}
import akka.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

object RecoveryPermitterSpec {

  trait State

  object EmptyState extends State

  object EventState extends State

  trait Command

  case object Stop extends Command

  trait Event

  case class Recovered(state: State) extends Event

  case object Ping extends Event

  private def persistentBehavior(name: String,
                                 commandProbe: TestProbe[Command],
                                 eventProbe: TestProbe[Event]): Behavior[Command] =
    PersistentBehaviors.immutable[Command, Event, State](
      persistenceId = name,
      initialState = EmptyState,
      commandHandler = CommandHandler.command { command ⇒ commandProbe.ref ! command; Effect.none },
      eventHandler = { (state, event) ⇒ eventProbe.ref ! event; state }
    ).onRecoveryCompleted { case (_, state) ⇒ eventProbe.ref ! Recovered(state) }
}

class RecoveryPermitterSpec extends ActorTestKit with TypedAkkaSpecWithShutdown with Eventually {

  override def config: Config = ConfigFactory.parseString(
    s"""
        akka.persistence.max-concurrent-recoveries = 3
        akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
        akka.actor.warn-about-java-serializer-usage = off
      """)

  val p1 = TestProbe[InternalProtocol]()
  val p2 = TestProbe[InternalProtocol]()
  val p3 = TestProbe[InternalProtocol]()
  val p4 = TestProbe[InternalProtocol]()
  val p5 = TestProbe[InternalProtocol]()

  def requestPermit[T](p: TestProbe[InternalProtocol]): Unit = {

  }

  "RecoveryPermitter" must {
    "grant permits up to the limit" in {

    }

    "grant recovery when all permits not used" in {

    }

    "delay recovery when all permits used" in {

    }

    "return permit when actor is pre-maturely terminated before holding permit" in {

    }

    "return permit when actor is pre-maturely terminated when holding permit" in {

    }

    "return permit when actor throws from RecoveryCompleted" in {

    }
  }
}
