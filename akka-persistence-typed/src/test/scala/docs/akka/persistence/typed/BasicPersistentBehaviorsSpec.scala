/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors

object BasicPersistentBehaviorsSpec {

  //#structure
  sealed trait Command
  sealed trait Event
  case class State()

  val behavior: Behavior[Command] =
    PersistentBehaviors[Command, Event, State]
      .identifiedBy("abc")
      .onCreation(???, ???)
      .onUpdate(???, ???)
  //#structure

  //#recovery
  val recoveryBehavior: Behavior[Command] =
    PersistentBehaviors[Command, Event, State]
      .identifiedBy("abc")
      .onCreation(???, ???)
      .onUpdate(???, ???)
      .onRecoveryCompleted { (ctx, state) ⇒
        ???
      }
  //#recovery

  //#tagging
  val taggingBehavior: Behavior[Command] =
    PersistentBehaviors[Command, Event, State]
      .identifiedBy("abc")
      .onCreation(???, ???)
      .onUpdate(???, ???)
      .withTagger(_ ⇒ Set("tag1", "tag2"))
  //#tagging

  //#wrapPersistentBehavior
  val samplePersistentBehavior =
    PersistentBehaviors[Command, Event, State]
      .identifiedBy("abc")
      .onCreation(???, ???)
      .onUpdate(???, ???)
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
