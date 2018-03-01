package akka.persistence.typed.internal

import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.internal.TimerSchedulerImpl
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.TimerScheduler
import akka.annotation.InternalApi
import akka.persistence.Recovery
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import akka.util.ConstantFun

/** INTERNAL API */
@InternalApi
private[persistence] class PersistentBehaviorImpl[Command, Event, State] private (
  persistenceId:  String,
  initialState:   State,
  commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],
  eventHandler:   (State, Event) ⇒ State,

  recoveryCompleted: (ActorContext[Command], State) ⇒ Unit,
  tagger:            Event ⇒ Set[String],
  journalPluginId:   String,
  snapshotPluginId:  String,
  snapshotWhen:      (State, Event, Long) ⇒ Boolean,
  recovery:          Recovery
) extends DeferredBehavior[Command](ctx ⇒
  TimerSchedulerImpl.wrapWithTimers[Command] { timers ⇒
    val callbacks = EventsourcedCallbacks[Command, Event, State](
      initialState,
      commandHandler,
      eventHandler,
      snapshotWhen,
      recoveryCompleted,
      tagger
    )
    val pluginIds = EventsourcedPluginIds(
      journalPluginId,
      snapshotPluginId
    )
    new EventsourcedRequestingRecoveryPermit(
      persistenceId,
      ctx.asInstanceOf[ActorContext[Any]], // sorry
      timers.asInstanceOf[TimerScheduler[Any]], // sorry
      recovery,
      callbacks,
      pluginIds
    ).narrow[Command]

  }(ctx)) with PersistentBehavior[Command, Event, State] {

  def this(
    persistenceId:  String,
    initialState:   State,
    commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State) {
    this(
      persistenceId,
      initialState,
      commandHandler,
      eventHandler,
      recoveryCompleted = ConstantFun.scalaAnyTwoToUnit,
      tagger = (_: Event) ⇒ Set.empty[String],
      journalPluginId = "" /* default plugin */ ,
      snapshotPluginId = "" /* default plugin */ ,
      snapshotWhen = ConstantFun.scalaAnyThreeToFalse,
      recovery = Recovery()
    )
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
  def withPersistencePluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "persistence plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = id)
  }

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(snapshotPluginId = id)
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

  private def copy(
    initialState:      State                                 = initialState,
    commandHandler:    CommandHandler[Command, Event, State] = commandHandler,
    eventHandler:      (State, Event) ⇒ State                = eventHandler,
    recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = recoveryCompleted,
    tagger:            Event ⇒ Set[String]                   = tagger,
    snapshotWhen:      (State, Event, Long) ⇒ Boolean        = snapshotWhen,
    journalPluginId:   String                                = journalPluginId,
    snapshotPluginId:  String                                = snapshotPluginId,
    recovery:          Recovery                              = recovery): PersistentBehavior[Command, Event, State] =
    new PersistentBehaviorImpl[Command, Event, State](
      persistenceId = persistenceId,
      initialState = initialState,
      commandHandler = commandHandler,
      eventHandler = eventHandler,
      recoveryCompleted = recoveryCompleted,
      tagger = tagger,
      journalPluginId = journalPluginId,
      snapshotPluginId = snapshotPluginId,
      snapshotWhen = snapshotWhen,
      recovery = recovery)

}

