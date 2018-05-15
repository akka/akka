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
         SnapshotManagement(ctx).deleteSnapshotsPriorTo(persistenceId, SnapshotSelectionCriteria.Latest)
       val eventsUpToLastSnapshotDeleted: Future[Done] =
         JournalManagement(ctx).deleteEventsUntil(persistenceId, sequenceNumber - 11)

       snapshotsBeforePreviousSnapshotDeleted.zip(eventsUpToLastSnapshotDeleted).onComplete(maybeDone =>
         // another chance to side effect

       )
     }

  // alternative, more declarative idea
  def behavior(entityId: String): Behavior[Command] =
    PersistentBehaviors.receive[Command, Modified, State](
      persistenceId = "State-" + entityId,
      initialState = State.empty,
      commandHandler,
      eventHandler
    ).withSnapshots(SnapshotSettings.every(5).keep(2).deletePreviousEvents())
    .withSnapshots(SnapshotSettings.snapshotWhen(predicate).keep(1))


  def someArbitraryPartOfTheSystem(): Unit = {
    val listOfPersistenceIds = List("a-one", "a-two", "a-one-two-thre-four")

    // user would have to somehow make sure they don't start the persistent actor/have it running though
    val journalManagement = JournalManagement(system, journalPluginId)
    val snapshotManagement = SnapshotManagement(system, snapshotPluginId)
    listOfPersistenceIds.foreach { id =>
      journalManagement.deleteEventsUntil(id, Long.MaxValue).flatMap(_ =>
        snapshotManagement.deleteSnapshotsPriorTo(persistenceId, SnapshotSelectionCriteria(Long.MaxValue))
      )
    }

    // maybe access to the management apis should be through a typed Persistence extension
    // for easier discoverability?
    Persistence(system).journalManagement(journalPluginId)
  }
}

