/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.typed.EventSourcedBehaviorLoggingSpec.ChattyEventSourcingBehavior.Hello
import akka.persistence.typed.EventSourcedBehaviorLoggingSpec.ChattyEventSourcingBehavior.Hellos
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.serialization.jackson.CborSerializable
import org.scalatest.wordspec.AnyWordSpecLike

object EventSourcedBehaviorLoggingSpec {

  object ChattyEventSourcingBehavior {
    sealed trait Command

    case class Hello(msg: String) extends Command
    case class Hellos(msg1: String, msg2: String) extends Command

    final case class Event(msg: String) extends CborSerializable

    def apply(id: PersistenceId): Behavior[Command] = {
      Behaviors.setup { ctx =>
        EventSourcedBehavior[Command, Event, Set[Event]](
          id,
          Set.empty,
          (_, command) =>
            command match {
              case Hello(msg) =>
                ctx.log.info("received message '{}'", msg)
                Effect.persist(Event(msg))

              case Hellos(msg1, msg2) =>
                Effect.persist(Event(msg1), Event(msg2))
            },
          (state, event) => state + event)
      }
    }
  }
}

class EventSourcedBehaviorLoggingSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config)
    with AnyWordSpecLike
    with LogCapturing {
  import EventSourcedBehaviorLoggingSpec._

  "Chatty behavior" must {
    val myId = PersistenceId("Chatty", "chat-1")
    val chattyActor = spawn(ChattyEventSourcingBehavior(myId))

    "log user message in context.log" in {
      LoggingTestKit
        .info("received message 'Mary'")
        .withLoggerName("akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$")
        .expect {
          chattyActor ! Hello("Mary")
        }
    }

    "log internal messages in 'Internal' logger without logging user data (Persist)" in {
      LoggingTestKit
        .debug(
          "Handled command [akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Hello], " +
          "resulting effect: [Persist(akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Event)], side effects: [0]")
        .withLoggerName("akka.persistence.typed.internal.EventSourcedBehaviorImpl")
        .expect {
          chattyActor ! Hello("Joe")
        }
    }

    "log internal messages in 'Internal' logger without logging user data (PersistAll)" in {
      LoggingTestKit
        .debug("Handled command [akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Hellos], " +
        "resulting effect: [PersistAll(akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Event," +
        "akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Event)], side effects: [0]")
        .withLoggerName("akka.persistence.typed.internal.EventSourcedBehaviorImpl")
        .expect {
          chattyActor ! Hellos("Mary", "Joe")
        }
    }

    "Internal logger preserves MDC source" in {
      LoggingTestKit
        .debug("Handled command ")
        .withLoggerName("akka.persistence.typed.internal.EventSourcedBehaviorImpl")
        .withMdc(Map("persistencePhase" -> "running-cmd", "persistenceId" -> "Chatty|chat-1"))
        .expect {
          chattyActor ! Hello("Mary")
        }
    }
  }
}
