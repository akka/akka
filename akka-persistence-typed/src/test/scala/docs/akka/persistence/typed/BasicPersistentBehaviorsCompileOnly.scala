/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors

object BasicPersistentBehaviorsCompileOnly {

  //#structure
  sealed trait Command
  sealed trait Event
  case class State()

  val behavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???)
  //#structure

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???)
      .onRecoveryCompleted { (ctx, state) ⇒
        ???
      }
  //#recovery

  //#tagging
  val taggingBehavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      emptyState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???
    ).withTagger(_ ⇒ Set("tag1", "tag2"))

  //#tagging

  //#wrapPersistentBehavior
  val samplePersistentBehavior = PersistentBehaviors.receive[Command, Event, State](
    persistenceId = "abc",
    emptyState = State(),
    commandHandler = (ctx, state, cmd) ⇒ ???,
    eventHandler = (state, evt) ⇒ ???)
    .onRecoveryCompleted { (ctx, state) ⇒
      ???
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
}
