/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.{ Behavior, SupervisorStrategy }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors

import scala.concurrent.duration._

object BasicPersistentBehaviorsCompileOnly {

  //#structure
  sealed trait Command
  sealed trait Event
  case class State()

  val behavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler =
        (ctx, state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    )
  //#structure

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler =
        (ctx, state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    ).onRecoveryCompleted { (ctx, state) ⇒
        throw new RuntimeException("TODO: add some end-of-recovery side-effect here")
      }
  //#recovery

  //#tagging
  val taggingBehavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler =
        (ctx, state, cmd) ⇒
          throw new RuntimeException("TODO: process the command & return an Effect"),
      eventHandler =
        (state, evt) ⇒
          throw new RuntimeException("TODO: process the event return the next state")
    ).withTagger(_ ⇒ Set("tag1", "tag2"))

  //#tagging

  //#wrapPersistentBehavior
  val samplePersistentBehavior = PersistentBehaviors.receive[Command, Event, State](
    persistenceId = "abc",
    emptyState = State(),
    commandHandler =
      (ctx, state, cmd) ⇒
        throw new RuntimeException("TODO: process the command & return an Effect"),
    eventHandler =
      (state, evt) ⇒
        throw new RuntimeException("TODO: process the event return the next state")
  ).onRecoveryCompleted { (ctx, state) ⇒
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
