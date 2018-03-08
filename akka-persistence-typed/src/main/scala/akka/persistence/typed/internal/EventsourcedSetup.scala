/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.internal

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.{ actor ⇒ a }

/**
 * INTERNAL API: Carry state for the Persistent behavior implementation behaviors
 */
@InternalApi
private[persistence] final case class EventsourcedSetup[C, E, S](
  context:                   ActorContext[InternalProtocol],
  timers:                    TimerScheduler[InternalProtocol],
  persistenceId:             String,
  initialState:              S,
  commandHandler:            PersistentBehaviors.CommandHandler[C, E, S],
  eventHandler:              (S, E) ⇒ S,
  writerIdentity:            WriterIdentity,
  recoveryCompleted:         (ActorContext[C], S) ⇒ Unit,
  tagger:                    E ⇒ Set[String],
  snapshotWhen:              (S, E, Long) ⇒ Boolean,
  recovery:                  Recovery,
  var holdingRecoveryPermit: Boolean,
  settings:                  EventsourcedSettings,
  internalStash:             StashBuffer[InternalProtocol] // FIXME would be nice here... but stash is mutable :\\\\\\\
) {
  import akka.actor.typed.scaladsl.adapter._

  def withJournalPluginId(id: String): EventsourcedSetup[C, E, S] = {
    require(id != null, "journal plugin id must not be null; use empty string for 'default' journal")
    copy(settings = settings.withJournalPluginId(id))
  }

  def withSnapshotPluginId(id: String): EventsourcedSetup[C, E, S] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(settings = settings.withSnapshotPluginId(id))
  }

  def commandContext: ActorContext[C] = context.asInstanceOf[ActorContext[C]]

  def log = context.log

  val persistence: Persistence = Persistence(context.system.toUntyped)

  val journal: ActorRef = persistence.journalFor(settings.journalPluginId)
  val snapshotStore: ActorRef = persistence.snapshotStoreFor(settings.snapshotPluginId)

  def selfUntyped = context.self.toUntyped

}

