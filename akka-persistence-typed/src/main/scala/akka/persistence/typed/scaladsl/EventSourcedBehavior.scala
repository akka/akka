/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.Done
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior.DeferredBehavior
import akka.annotation.DoNotInherit
import akka.persistence._
import akka.persistence.typed.{ EventAdapter, ExpectingReply, PersistenceId }
import akka.persistence.typed.internal._

import scala.util.Try

object EventSourcedBehavior {

  /**
   * Type alias for the command handler function that defines how to act on commands.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type CommandHandler[Command, Event, State] = (State, Command) ⇒ Effect[Event, State]

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
  def apply[Command, Event, State](
    persistenceId:  PersistenceId,
    emptyState:     State,
    commandHandler: (State, Command) ⇒ Effect[Event, State],
    eventHandler:   (State, Event) ⇒ State): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehaviorImpl(persistenceId, emptyState, commandHandler, eventHandler)

  /**
   * Create a `Behavior` for a persistent actor that is enforcing that replies to commands are not forgotten.
   * Then there will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
   * created with [[Effect.reply]], [[Effect.noReply]], [[Effect.thenReply]], or [[Effect.thenNoReply]].
   */
  def withEnforcedReplies[Command <: ExpectingReply[_], Event, State](
    persistenceId:  PersistenceId,
    emptyState:     State,
    commandHandler: (State, Command) ⇒ ReplyEffect[Event, State],
    eventHandler:   (State, Event) ⇒ State): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehaviorImpl(persistenceId, emptyState, commandHandler, eventHandler)

  /**
   * The `CommandHandler` defines how to act on commands. A `CommandHandler` is
   * a function:
   *
   * {{{
   *   (State, Command) ⇒ Effect[Event, State]
   * }}}
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

  }

}

/**
 * Further customization of the `PersistentBehavior` can be done with the methods defined here.
 *
 * Not for user extension
 */
@DoNotInherit trait EventSourcedBehavior[Command, Event, State] extends DeferredBehavior[Command] {

  def persistenceId: PersistenceId

  /**
   * The `callback` function is called to notify that the recovery process has finished.
   */
  def onRecoveryCompleted(callback: State ⇒ Unit): EventSourcedBehavior[Command, Event, State]
  /**
   * The `callback` function is called to notify that recovery has failed. For setting a supervision
   * strategy `onPersistFailure`
   */
  def onRecoveryFailure(callback: Throwable ⇒ Unit): EventSourcedBehavior[Command, Event, State]

  /**
   * The `callback` function is called to notify when a snapshot is complete.
   */
  def onSnapshot(callback: (SnapshotMetadata, Try[Done]) ⇒ Unit): EventSourcedBehavior[Command, Event, State]

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): EventSourcedBehavior[Command, Event, State]
  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): EventSourcedBehavior[Command, Event, State]

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): EventSourcedBehavior[Command, Event, State]

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): EventSourcedBehavior[Command, Event, State]

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): EventSourcedBehavior[Command, Event, State]

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): EventSourcedBehavior[Command, Event, State]

  /**
   * Transform the event in another type before giving to the journal. Can be used to wrap events
   * in types Journals understand but is of a different type than `Event`.
   */
  def eventAdapter(adapter: EventAdapter[Event, _]): EventSourcedBehavior[Command, Event, State]

  /**
   * Back off strategy for persist failures.
   *
   * Specifically BackOff to prevent resume being used. Resume is not allowed as
   * it will be unknown if the event has been persisted.
   *
   * If not specified the actor will be stopped on failure.
   */
  def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): EventSourcedBehavior[Command, Event, State]
}

