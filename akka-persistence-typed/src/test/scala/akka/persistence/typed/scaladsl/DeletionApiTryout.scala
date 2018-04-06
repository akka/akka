/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.Done
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler

import scala.concurrent.Future

object DeletionApiTryout {

  object State {
    val empty = State(0)
  }

  final case class State(state: Int) {}

  sealed trait Command
  final case class Modify(n: Int) extends Command

  case class Modified(mod: Int)

  private def commandHandler: CommandHandler[Command, Modified, State] =
    (ctx, state, cmd) ⇒
      cmd match {
        case Modify(n) ⇒
          Effect.persist(Modified(n))
      }

  private def eventHandler(state: State, event: Modified): State =
    state.copy(state = state.state + event.mod)

  def behavior(entityId: String): Behavior[Command] =
    PersistentBehaviors.receive[Command, Modified, State](
      persistenceId = "State-" + entityId,
      initialState = State.empty,
      commandHandler,
      eventHandler
    ).snapshotEvery(5)
     .afterSnapshot { (ctx, state, event, persistenceId, sequenceNumber) =>
       // purely side-effect stuff here - feels bad, but alternative would be a declarative
       // way of describing what deletions to perform - which also feels bad
       val snapshotsBeforePreviousSnapshotDeleted: Future[Done] =
         SnapshotManagement(ctx).deleteSnapshots(persistenceId, SnapshotSelectionCriteria(sequenceNumber - 11))
       val eventsUpToLastSnapshotDeleted: Future[Done] =
         JournalManagement(ctx).deleteEvents(persistenceId, sequenceNumber - 11)

       snapshotsBeforePreviousSnapshotDeleted.zip(eventsUpToLastSnapshotDeleted).onComplete(maybeDone =>
         // another chance to side effect

       )
     }
}

