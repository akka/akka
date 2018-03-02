/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.internal

import akka.actor.ActorRef
import akka.{ actor ⇒ a }
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol.RecoveryPermitGranted
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence._
import akka.util.ConstantFun

@InternalApi
private[persistence] object EventsourcedSetup {

  def apply[Command, Event, State](
    context: ActorContext[InternalProtocol],
    timers:  TimerScheduler[InternalProtocol],

    persistenceId:  String,
    initialState:   State,
    commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State): EventsourcedSetup[Command, Event, State] = {
    apply(
      context,
      timers,
      persistenceId,
      initialState,
      commandHandler,
      eventHandler,
      // values dependent on context
      EventsourcedSettings(context.system))
  }

  def apply[Command, Event, State](
    context: ActorContext[InternalProtocol],
    timers:  TimerScheduler[InternalProtocol],

    persistenceId:  String,
    initialState:   State,
    commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State,
    settings:       EventsourcedSettings): EventsourcedSetup[Command, Event, State] = {
    new EventsourcedSetup[Command, Event, State](
      context,
      timers,

      persistenceId,
      initialState,
      commandHandler,
      eventHandler,
      writerIdentity = WriterIdentity.newIdentity(),
      recoveryCompleted = ConstantFun.scalaAnyTwoToUnit,
      tagger = (_: Event) ⇒ Set.empty[String],
      snapshotWhen = ConstantFun.scalaAnyThreeToFalse,
      recovery = Recovery(),
      settings,
      StashBuffer(settings.stashCapacity)
    )
  }
}

/** INTERNAL API: Carry state for the Persistent behavior implementation behaviors */
@InternalApi
private[persistence] final case class EventsourcedSetup[Command, Event, State](
  context: ActorContext[InternalProtocol],
  timers:  TimerScheduler[InternalProtocol],

  persistenceId:  String,
  initialState:   State,
  commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],

  eventHandler:      (State, Event) ⇒ State,
  writerIdentity:    WriterIdentity,
  recoveryCompleted: (ActorContext[Command], State) ⇒ Unit,
  tagger:            Event ⇒ Set[String],
  snapshotWhen:      (State, Event, Long) ⇒ Boolean,
  recovery:          Recovery,

  settings: EventsourcedSettings,

  internalStash: StashBuffer[InternalProtocol] // FIXME would be nice here... but stash is mutable :\\\\\\\
) {
  import akka.actor.typed.scaladsl.adapter._

  def withJournalPluginId(id: Option[String]): EventsourcedSetup[Command, Event, State] = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(settings = settings.withJournalPluginId(id))
  }

  def withSnapshotPluginId(id: Option[String]): EventsourcedSetup[Command, Event, State] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(settings = settings.withSnapshotPluginId(id))
  }

  def commandContext: ActorContext[Command] = context.asInstanceOf[ActorContext[Command]]

  def log = context.log

  val persistence: Persistence = Persistence(context.system.toUntyped)

  val journal: ActorRef = persistence.journalFor(settings.journalPluginId)
  val snapshotStore: ActorRef = persistence.snapshotStoreFor(settings.snapshotPluginId)

  def selfUntyped = context.self.toUntyped

  import EventsourcedBehavior.InternalProtocol
  val selfUntypedAdapted: a.ActorRef = context.messageAdapter[Any] {
    case res: JournalProtocol.Response           ⇒ InternalProtocol.JournalResponse(res)
    case RecoveryPermitter.RecoveryPermitGranted ⇒ InternalProtocol.RecoveryPermitGranted
    case res: SnapshotProtocol.Response          ⇒ InternalProtocol.SnapshotterResponse(res)
    case cmd: Command @unchecked                 ⇒ InternalProtocol.IncomingCommand(cmd)
  }.toUntyped

}

