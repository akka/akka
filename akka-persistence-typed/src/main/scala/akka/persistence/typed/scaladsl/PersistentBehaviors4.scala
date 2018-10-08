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
- to avoid repeating type parameters when several command handlers are defined a HandlerFactory
  can be used
- see AccountExample4
*/

object PersistentBehaviors4 {

  class HandlerFactory[Command, Event, State] {
    def commandHandler(handler: (State, Command) ⇒ Effect[Event, State]): (State, Command) ⇒ Effect[Event, State] =
      handler

    def subStateCommandHandler[S <: State](handler: (S, Command) ⇒ Effect[Event, State]): (S, Command) ⇒ Effect[Event, State] =
      handler

    /**
     * Convenience for simple commands that don't need the state.
     *
     * @see [[Effect]] for possible effects of a command.
     */
    def command(handler: Command ⇒ Effect[Event, State]): (State, Command) ⇒ Effect[Event, State] =
      (_, cmd) ⇒ handler(cmd)

    def eventHandler(handler: (State, Event) ⇒ State): (State, Event) ⇒ State =
      handler
  }

  /**
   * Type alias for the command handler function that defines how to act on commands.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type CommandHandler[Command, Event, State] = (State, Command) ⇒ Effect[Event, State]

  /**
   * Type alias for the command handler function for a subclass of the `State` that defines how to act on commands.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type SubStateCommandHandler[Command, Event, State, S <: State] = (S, Command) ⇒ Effect[Event, State]

  /**
   * Type alias for the event handler function for updating the state based on events having been persisted.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type EventHandler[State, Event] = (State, Event) ⇒ State

  /**
   * Create a `Behavior` for a persistent actor.
   */
  def receive[Command, Event, State](
    persistenceId:  String,
    emptyState:     State,
    commandHandler: (State, Command) ⇒ Effect[Event, State],
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior4[Command, Event, State] = ???

}

trait PersistentBehavior4[Command, Event, State] extends DeferredBehavior[Command] {
  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: State ⇒ Unit): PersistentBehavior4[Command, Event, State]

  /**
   * The `callback` function is called to notify when a snapshot is complete.
   */
  def onSnapshot(callback: (SnapshotMetadata, Try[Done]) ⇒ Unit): PersistentBehavior4[Command, Event, State]

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior4[Command, Event, State]
  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior4[Command, Event, State]

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): PersistentBehavior4[Command, Event, State]

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior4[Command, Event, State]

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior4[Command, Event, State]

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior4[Command, Event, State]

  /**
   * Transform the event in another type before giving to the journal. Can be used to wrap events
   * in types Journals understand but is of a different type than `Event`.
   */
  def eventAdapter(adapter: EventAdapter[Event, _]): PersistentBehavior4[Command, Event, State]

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): PersistentBehavior4[Command, Event, State]
}

