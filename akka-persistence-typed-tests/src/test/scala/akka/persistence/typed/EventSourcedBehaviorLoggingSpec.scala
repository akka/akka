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
import com.typesafe.config.{ Config, ConfigFactory }
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

abstract class EventSourcedBehaviorLoggingSpec(config: Config)
    extends ScalaTestWithActorTestKit(config)
    with AnyWordSpecLike
    with LogCapturing {
  import EventSourcedBehaviorLoggingSpec._

  def loggerName: String
  def loggerId: String

  s"Chatty behavior ($loggerId)" must {
    val myId = PersistenceId("Chatty", "chat-1")
    val chattyActor = spawn(ChattyEventSourcingBehavior(myId))

    "always log user message in context.log" in {
      LoggingTestKit
        .info("received message 'Mary'")
        .withLoggerName("akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$")
        .expect {
          chattyActor ! Hello("Mary")
        }
    }

    s"log internal messages in '$loggerId' logger without logging user data (Persist)" in {
      LoggingTestKit
        .debug(
          "Handled command [akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Hello], " +
          "resulting effect: [Persist(akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Event)], side effects: [0]")
        .withLoggerName(loggerName)
        .expect {
          chattyActor ! Hello("Joe")
        }
    }

    s"log internal messages in '$loggerId' logger without logging user data (PersistAll)" in {
      LoggingTestKit
        .debug("Handled command [akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Hellos], " +
        "resulting effect: [PersistAll(akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Event," +
        "akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$Event)], side effects: [0]")
        .withLoggerName(loggerName)
        .expect {
          chattyActor ! Hellos("Mary", "Joe")
        }
    }

    s"log in '$loggerId' while preserving MDC source" in {
      LoggingTestKit
        .debug("Handled command ")
        .withLoggerName(loggerName)
        .withMdc(Map("persistencePhase" -> "running-cmd", "persistenceId" -> "Chatty|chat-1"))
        .expect {
          chattyActor ! Hello("Mary")
        }
    }
  }
}

class EventSourcedBehaviorLoggingInternalLoggerSpec
    extends EventSourcedBehaviorLoggingSpec(PersistenceTestKitPlugin.config) {
  override def loggerName = "akka.persistence.typed.internal.EventSourcedBehaviorImpl"
  override def loggerId = "internal.log"
}

object EventSourcedBehaviorLoggingContextLoggerSpec {
  val config =
    ConfigFactory
      .parseString("akka.persistence.typed.use-context-logger-for-internal-logging = true")
      .withFallback(PersistenceTestKitPlugin.config)
}
class EventSourcedBehaviorLoggingContextLoggerSpec
    extends EventSourcedBehaviorLoggingSpec(EventSourcedBehaviorLoggingContextLoggerSpec.config) {
  override def loggerName = "akka.persistence.typed.EventSourcedBehaviorLoggingSpec$ChattyEventSourcingBehavior$"
  override def loggerId = "context.log"
}
