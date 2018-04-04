/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer }
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }
import akka.persistence.typed.scaladsl.{ PersistentBehavior, PersistentBehaviors }
import akka.util.ConstantFun

@InternalApi
private[akka] final case class PersistentBehaviorImpl[Command, Event, State](
  persistenceId:     String,
  initialState:      State,
  commandHandler:    PersistentBehaviors.CommandHandler[Command, Event, State],
  eventHandler:      (State, Event) ⇒ State,
  journalPluginId:   Option[String]                                            = None,
  snapshotPluginId:  Option[String]                                            = None,
  recoveryCompleted: (ActorContext[Command], State) ⇒ Unit                     = ConstantFun.scalaAnyTwoToUnit,
  tagger:            Event ⇒ Set[String]                                       = (_: Event) ⇒ Set.empty[String],
  snapshotWhen:      (State, Event, Long) ⇒ Boolean                            = ConstantFun.scalaAnyThreeToFalse,
  recovery:          Recovery                                                  = Recovery()
) extends PersistentBehavior[Command, Event, State] {

  override def apply(context: typed.ActorContext[Command]): Behavior[Command] = {
    Behaviors.setup[EventsourcedBehavior.InternalProtocol] { ctx ⇒
      Behaviors.withTimers { timers ⇒
        val settings = EventsourcedSettings(ctx.system)
          .withJournalPluginId(journalPluginId.getOrElse(""))
          .withSnapshotPluginId(snapshotPluginId.getOrElse(""))
        val setup = new EventsourcedSetup(
          ctx,
          timers,
          persistenceId,
          initialState,
          commandHandler,
          eventHandler,
          WriterIdentity.newIdentity(),
          recoveryCompleted,
          tagger,
          snapshotWhen,
          recovery,
          holdingRecoveryPermit = false,
          settings = settings,
          internalStash = StashBuffer(settings.stashCapacity)
        )

        EventsourcedRequestingRecoveryPermit(setup)
      }
    }.widen[Any] {
      case res: JournalProtocol.Response           ⇒ InternalProtocol.JournalResponse(res)
      case res: SnapshotProtocol.Response          ⇒ InternalProtocol.SnapshotterResponse(res)
      case RecoveryPermitter.RecoveryPermitGranted ⇒ InternalProtocol.RecoveryPermitGranted
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
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
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
