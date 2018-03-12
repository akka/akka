/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.scaladsl

import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.typed.internal._
import akka.persistence._
import akka.util.ConstantFun

import scala.language.implicitConversions

object PersistentBehaviors {

  // we use this type internally, however it's easier for users to understand the function, so we use it in external api
  type CommandHandler[Command, Event, State] = (ActorContext[Command], State, Command) ⇒ Effect[Event, State]

  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] =
    PersistentBehaviorImpl(persistenceId, initialState, commandHandler, eventHandler)

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
      (_, _, cmd) ⇒ commandHandler(cmd)

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

    override def apply(ctx: ActorContext[Command], state: State, cmd: Command): Effect[Event, State] =
      choice(state)(ctx, state, cmd)

  }
}

trait PersistentBehavior[Command, Event, State] extends DeferredBehavior[Command] {
  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State]

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
   * You may configure the behavior to skip recovering snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior[Command, Event, State]

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State]
}

@InternalApi
private[akka] final case class PersistentBehaviorImpl[Command, Event, State](
  persistenceId:  String,
  initialState:   State,
  commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],
  eventHandler:   (State, Event) ⇒ State,

  journalPluginId:  Option[String] = None,
  snapshotPluginId: Option[String] = None,
  // settings: Option[EventsourcedSettings], // FIXME can't because no context available yet

  recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = ConstantFun.scalaAnyTwoToUnit,
  tagger:            Event ⇒ Set[String]                   = (_: Event) ⇒ Set.empty[String],
  snapshotWhen:      (State, Event, Long) ⇒ Boolean        = ConstantFun.scalaAnyThreeToFalse,
  recovery:          Recovery                              = Recovery()
) extends PersistentBehavior[Command, Event, State] {

  override def apply(context: typed.ActorContext[Command]): Behavior[Command] = {
    Behaviors.setup[EventsourcedBehavior.InternalProtocol] { ctx ⇒
      Behaviors.withTimers[EventsourcedBehavior.InternalProtocol] { timers ⇒
        val setup = EventsourcedSetup(
          ctx,
          timers,
          persistenceId,
          initialState,
          commandHandler,
          eventHandler)
          .withJournalPluginId(journalPluginId)
          .withSnapshotPluginId(snapshotPluginId)

        EventsourcedRequestingRecoveryPermit(setup)
      }
    }.widen[Any] {
      case res: JournalProtocol.Response           ⇒ InternalProtocol.JournalResponse(res)
      case RecoveryPermitter.RecoveryPermitGranted ⇒ InternalProtocol.RecoveryPermitGranted
      case res: SnapshotProtocol.Response          ⇒ InternalProtocol.SnapshotterResponse(res)
      case cmd: Command @unchecked                 ⇒ InternalProtocol.IncomingCommand(cmd)
    }.narrow[Command]
  }

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior[Command, Event, State] =
    copy(snapshotWhen = predicate)

  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotWhen = (_, _, seqNr) ⇒ seqNr % numberOfEvents == 0)
  }

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withJournalPluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = if (id != "") Some(id) else None)
  }

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(snapshotPluginId = if (id != "") Some(id) else None)
  }

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip recovering snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior[Command, Event, State] = {
    copy(recovery = Recovery(selection))
  }

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State] =
    copy(tagger = tagger)

}
