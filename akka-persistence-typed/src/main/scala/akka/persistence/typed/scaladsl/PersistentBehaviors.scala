/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.Done
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.internal._

import scala.util.Try

object PersistentBehaviors {

  /**
   * Type alias for the command handler function for reacting on events having been persisted.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type CommandHandler[Command, Event, State] = (State, Command) ⇒ Effect[Event, State]

  /**
   * Type alias for the event handler function defines how to act on commands.
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
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] =
    PersistentBehaviorImpl(persistenceId, emptyState, commandHandler, eventHandler)

  /**
   * The `CommandHandler` defines how to act on commands. A `CommandHandler` is
   * a function:
   *
   * {{{
   *   (ActorContext[Command], State, Command) ⇒ Effect[Event, State]
   * }}}
   *
   * Note that you can have different command handlers based on current state by using
   * [[CommandHandler#byState]].
   *
   * The [[CommandHandler#command]] is useful for simple commands that don't need the state
   * and context.
   */
  object CommandHandler {

    /**
     * Convenience for simple commands that don't need the state and context.
     *
     * @see [[Effect]] for possible effects of a command.
     */
    def command[Command, Event, State](commandHandler: Command ⇒ Effect[Event, State]): (State, Command) ⇒ Effect[Event, State] =
      (_, cmd) ⇒ commandHandler(cmd)

    /**
     * Select different command handlers based on current state.
     */
    def byState[Command, Event, State](
      choice: State ⇒ (State, Command) ⇒ Effect[Event, State]): (State, Command) ⇒ Effect[Event, State] = {
      new ByStateCommandHandler(choice)
    }

  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final class ByStateCommandHandler[Command, Event, State](
    choice: State ⇒ CommandHandler[Command, Event, State])
    extends CommandHandler[Command, Event, State] {

    override def apply(state: State, cmd: Command): Effect[Event, State] =
      choice(state)(state, cmd)

  }
}

trait PersistentBehavior[Command, Event, State] extends DeferredBehavior[Command] {
  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State]

  /**
   * The `callback` function is called to notify when a snapshot is complete.
   */
  def onSnapshot(callback: (ActorContext[Command], SnapshotMetadata, Try[Done]) ⇒ Unit): PersistentBehavior[Command, Event, State]

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior[Command, Event, State]
  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior[Command, Event, State]

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): PersistentBehavior[Command, Event, State]

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior[Command, Event, State]

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior[Command, Event, State]

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State]

  /**
   * Transform the event in another type before giving to the journal. Can be used to wrap events
   * in types Journals understand but is of a different type than `Event`.
   */
  def eventAdapter(adapter: EventAdapter[Event, _]): PersistentBehavior[Command, Event, State]

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): PersistentBehavior[Command, Event, State]
}

