/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import scala.util.Try
import akka.Done
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior.DeferredBehavior
import akka.persistence._
import akka.persistence.typed.EventAdapter

/*
API experiment with factory for command and event handler
- commandHandler and eventHandler defined as functions as before, without enclosing class
- no HandlerFactory
- see AccountExample6
*/

object PersistentBehaviors6 {

  class HandlerFactory[Command, Event, State] {
    type Effect = akka.persistence.typed.scaladsl.Effect[Event, State]

    type CommandHandler = Command ⇒ Effect
    type EventHandler = Event ⇒ State

    object CommandHandler {
      def apply(handler: Command ⇒ Effect): Command ⇒ Effect = handler
      def unhandled: Command ⇒ Effect = _ ⇒ Effect.unhandled[Event, State]
      def partial(handler: PartialFunction[Command, Effect]): Command ⇒ Effect =
        handler.orElse {
          case _ ⇒ Effect.unhandled[Event, State]
        }
    }

    object EventHandler {
      def apply(handler: Event ⇒ State): Event ⇒ State = handler
      def unhandled: Event ⇒ State = event ⇒ throw new IllegalStateException(s"unexpected event [${event.getClass}]")
      def partial(handler: PartialFunction[Event, State]): Event ⇒ State =
        handler.orElse {
          case event ⇒
            throw new IllegalStateException(s"unexpected event [${event.getClass}]")
        }
    }

  }

  /**
   * Create a `Behavior` for a persistent actor.
   */
  def receive[Command, Event, State](
    persistenceId:  String,
    emptyState:     State,
    commandHandler: (State, Command) ⇒ Effect[Event, State],
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior4[Command, Event, State] = ???

}

trait PersistentBehavior6[Command, Event, State] extends DeferredBehavior[Command] {
  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: State ⇒ Unit): PersistentBehavior6[Command, Event, State]

  /**
   * The `callback` function is called to notify when a snapshot is complete.
   */
  def onSnapshot(callback: (SnapshotMetadata, Try[Done]) ⇒ Unit): PersistentBehavior6[Command, Event, State]

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior6[Command, Event, State]
  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior6[Command, Event, State]

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): PersistentBehavior6[Command, Event, State]

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior6[Command, Event, State]

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior6[Command, Event, State]

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior6[Command, Event, State]

  /**
   * Transform the event in another type before giving to the journal. Can be used to wrap events
   * in types Journals understand but is of a different type than `Event`.
   */
  def eventAdapter(adapter: EventAdapter[Event, _]): PersistentBehavior6[Command, Event, State]

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): PersistentBehavior6[Command, Event, State]
}

