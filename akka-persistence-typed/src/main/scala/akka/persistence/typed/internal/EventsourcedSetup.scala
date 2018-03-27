/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Logger
import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.MDC
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.util.Collections.EmptyImmutableSeq
import akka.util.OptionVal

/**
 * INTERNAL API: Carry state for the Persistent behavior implementation behaviors
 */
@InternalApi
private[persistence] final class EventsourcedSetup[C, E, S](
  val context:               ActorContext[InternalProtocol],
  val timers:                TimerScheduler[InternalProtocol],
  val persistenceId:         String,
  val initialState:          S,
  val commandHandler:        PersistentBehaviors.CommandHandler[C, E, S],
  val eventHandler:          (S, E) ⇒ S,
  val writerIdentity:        WriterIdentity,
  val recoveryCompleted:     (ActorContext[C], S) ⇒ Unit,
  val tagger:                E ⇒ Set[String],
  val snapshotWhen:          (S, E, Long) ⇒ Boolean,
  val recovery:              Recovery,
  var holdingRecoveryPermit: Boolean,
  val settings:              EventsourcedSettings,
  val internalStash:         StashBuffer[InternalProtocol]
) {
  import akka.actor.typed.scaladsl.adapter._

  def commandContext: ActorContext[C] = context.asInstanceOf[ActorContext[C]]

  val persistence: Persistence = Persistence(context.system.toUntyped)

  val journal: ActorRef = persistence.journalFor(settings.journalPluginId)
  val snapshotStore: ActorRef = persistence.snapshotStoreFor(settings.snapshotPluginId)

  val internalStashOverflowStrategy: StashOverflowStrategy = {
    val system = context.system.toUntyped.asInstanceOf[ExtendedActorSystem]
    system.dynamicAccess.createInstanceFor[StashOverflowStrategyConfigurator](settings.stashOverflowStrategyConfigurator, EmptyImmutableSeq)
      .map(_.create(system.settings.config)).get
  }

  def selfUntyped = context.self.toUntyped

  private var mdc: Map[String, Any] = Map.empty
  private var _log: OptionVal[Logger] = OptionVal.Some(context.log) // changed when mdc is changed
  def log: Logger = {
    _log match {
      case OptionVal.Some(l) ⇒ l
      case OptionVal.None ⇒
        // lazy init if mdc changed
        val l = context.log.withMdc(mdc)
        _log = OptionVal.Some(l)
        l
    }
  }

  def setMdc(newMdc: Map[String, Any]): EventsourcedSetup[C, E, S] = {
    mdc = newMdc
    // mdc is changed often, for each persisted event, but logging is rare, so lazy init of Logger
    _log = OptionVal.None
    this
  }

  def setMdc(phaseName: String): EventsourcedSetup[C, E, S] = {
    setMdc(MDC.create(persistenceId, phaseName))
    this
  }

}

