/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.{ Behavior, SupervisorStrategy }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.PersistentBehavior

import scala.concurrent.duration._

object BasicPersistentBehaviorCompileOnly {

  //#structure
  sealed trait Command
  sealed trait Event
  case class State()

  val behavior: Behavior[Command] =
    PersistentBehavior[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler =
        (state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    )
  //#structure

  case class CommandWithSender(reply: ActorRef[String]) extends Command
  case class VeryImportantEvent() extends Event

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    PersistentBehavior[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler =
        (state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    ).onRecoveryCompleted { state ⇒
        throw new RuntimeException("TODO: add some end-of-recovery side-effect here")
      }
  //#recovery

  //#tagging
  val taggingBehavior: Behavior[Command] =
    PersistentBehavior[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler =
        (state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    ).withTagger(_ ⇒ Set("tag1", "tag2"))
  //#tagging

  //#wrapPersistentBehavior
  val samplePersistentBehavior = PersistentBehavior[Command, Event, State](
    persistenceId = "abc",
    emptyState = State(),
    commandHandler =
      (state, cmd) ⇒
        throw new RuntimeException("TODO: process the command & return an Effect"),
    eventHandler =
      (state, evt) ⇒
        throw new RuntimeException("TODO: process the event return the next state")
  ).onRecoveryCompleted { state ⇒
      throw new RuntimeException("TODO: add some end-of-recovery side-effect here")
    }

  val debugAlwaysSnapshot: Behavior[Command] = Behaviors.setup {
    context ⇒
      samplePersistentBehavior.snapshotWhen((state, _, _) ⇒ {
        context.log.info(
          "Snapshot actor {} => state: {}",
          context.self.path.name, state)
        true
      })
  }
  //#wrapPersistentBehavior

  //#supervision
  val supervisedBehavior = samplePersistentBehavior.onPersistFailure(
    SupervisorStrategy.restartWithBackoff(
      minBackoff = 10.seconds,
      maxBackoff = 60.seconds,
      randomFactor = 0.1
    ))
  //#supervision

}
