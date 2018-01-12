/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.persistence.typed.scaladsl.PersistentActor

object BasicPersistentActorSpec {

  //#structure
  sealed trait Command
  sealed trait Event
  case class State()

  val behavior: Behavior[Command] =
    PersistentActor.immutable[Command, Event, State](
      persistenceId = "abc",
      initialState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???)
  //#structure

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    PersistentActor.immutable[Command, Event, State](
      persistenceId = "abc",
      initialState = State(),
      commandHandler = (ctx, state, cmd) ⇒ ???,
      eventHandler = (state, evt) ⇒ ???)
      .onRecoveryCompleted { (ctx, state) ⇒
        ???
      }
  //#recovery

}
