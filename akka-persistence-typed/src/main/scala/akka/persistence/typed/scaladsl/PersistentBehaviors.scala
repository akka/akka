/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.internal._
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler

object PersistentBehaviors {

  // we use this type internally, however it's easier for users to understand the function, so we use it in external api
  type CommandHandler[Command, Event, State] = (ActorContext[Command], Command) ⇒ Effect[Event, State]
  type EventHandler[Event, State] = Event ⇒ State

  def apply[Command, Event, State] = new PersistentBehaviorBuilderIdentifiedBy[Command, Event, State]

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] final class PersistentBehaviorBuilderIdentifiedBy[Command, Event, State] {
    def identifiedBy(persistenceId: String) = new PersistentBehaviorBuilderOnCreation[Command, Event, State](persistenceId)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] final class PersistentBehaviorBuilderOnCreation[Command, Event, State](persistenceId: String) {

    def onCreation(
      commandHandler: CommandHandler[Command, Event, State],
      eventHandler:   EventHandler[Event, State]): PersistentBehaviorBuilderOnUpdate[Command, Event, State] =
      new PersistentBehaviorBuilderOnUpdate(persistenceId, commandHandler, eventHandler)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] final class PersistentBehaviorBuilderOnUpdate[Command, Event, State](
    persistenceId:            String,
    commandHandlerOnCreation: CommandHandler[Command, Event, State],
    eventHandlerOnCreation:   EventHandler[Event, State]) {

    def onUpdate(
      commandHandler: State ⇒ CommandHandler[Command, Event, State],
      eventHandler:   State ⇒ EventHandler[Event, State]): PersistentBehavior[Command, Event, State] = {

      // build the behavior with everything with have got
      PersistentBehaviorImpl(
        persistenceId = persistenceId,
        commandHandlerOnCreation = commandHandlerOnCreation,
        eventHandlerOnCreation = eventHandlerOnCreation,
        commandHandlerOnUpdate = commandHandler,
        eventHandlerOnUpdate = eventHandler
      )
    }
  }

  /**
   * The `CommandHandler` defines how to act on commands.
   *
   * Note that you can have different command handlers based on current state by using
   * [[CommandHandler#byState]].
   */
  object CommandHandler {

    /**
     * Convenience for simple commands that don't need the state and context.
     *
     * @see [[Effect]] for possible effects of a command.
     */
    def command[Command, Event, State](commandHandler: Command ⇒ Effect[Event, State]): CommandHandler[Command, Event, State] =
      (_, cmd) ⇒ commandHandler(cmd)

    /**
     * Select different command handlers based on current state.
     */
    def byState[Command, Event, State](choice: State ⇒ CommandHandler[Command, Event, State]): CommandHandler[Command, Event, State] =
      new ByStateCommandHandler(choice)

  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final class ByStateCommandHandler[Command, Event, State](
    choice: State ⇒ CommandHandler[Command, Event, State])
    extends CommandHandler[Command, Event, State] {

    override def apply(ctx: ActorContext[Command], cmd: Command): Effect[Event, State] = ???

  }

}

trait PersistentBehavior[Command, Event, State] extends DeferredBehavior[Command] {
  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], Option[State]) ⇒ Unit): PersistentBehavior[Command, Event, State]

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
}
