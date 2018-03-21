/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.PersistentBehaviors

object BasicPersistentBehaviorsSpec {

  //#structure
  sealed trait Command
  sealed trait Event
  case class State()

  val behavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      initialState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???)
  //#structure

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = "abc",
      initialState = State(),
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
      initialState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???
    ).withTagger(_ ⇒ Set("tag1", "tag2"))

  //#tagging
}
