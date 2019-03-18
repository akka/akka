/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.ExecutionContext

import akka.actor.Cancellable
import akka.actor.typed.Logger
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.ActorRef
import akka.actor.typed.Signal
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RetentionCriteria
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.util.ConstantFun
import akka.util.OptionVal

/**
 * INTERNAL API: Carry state for the Persistent behavior implementation behaviors.
 */
@InternalApi
private[akka] final class BehaviorSetup[C, E, S](
    val context: ActorContext[InternalProtocol],
    val persistenceId: PersistenceId,
    val emptyState: S,
    val commandHandler: EventSourcedBehavior.CommandHandler[C, E, S],
    val eventHandler: EventSourcedBehavior.EventHandler[S, E],
    val writerIdentity: EventSourcedBehaviorImpl.WriterIdentity,
    private val signalHandler: PartialFunction[Signal, Unit],
    val tagger: E ⇒ Set[String],
    val eventAdapter: EventAdapter[E, _],
    val snapshotWhen: (S, E, Long) ⇒ Boolean,
    val recovery: Recovery,
    val retention: RetentionCriteria,
    var holdingRecoveryPermit: Boolean,
    val settings: EventSourcedSettings,
    val stashState: StashState) {

  import InternalProtocol.RecoveryTickEvent
  import akka.actor.typed.scaladsl.adapter._

  val persistence: Persistence = Persistence(context.system.toUntyped)

  val journal: ActorRef = persistence.journalFor(settings.journalPluginId)
  val snapshotStore: ActorRef = persistence.snapshotStoreFor(settings.snapshotPluginId)

  def selfUntyped = context.self.toUntyped

  private var mdc: Map[String, Any] = Map.empty
  private var _log: OptionVal[Logger] = OptionVal.Some(context.log) // changed when mdc is changed
  def log: Logger = {
    _log match {
      case OptionVal.Some(l) => l
      case OptionVal.None    =>
        // lazy init if mdc changed
        val l = context.log.withMdc(mdc)
        _log = OptionVal.Some(l)
        l
    }
  }

  def setMdc(newMdc: Map[String, Any]): BehaviorSetup[C, E, S] = {
    mdc = newMdc
    // mdc is changed often, for each persisted event, but logging is rare, so lazy init of Logger
    _log = OptionVal.None
    this
  }

  def setMdc(phaseName: String): BehaviorSetup[C, E, S] = {
    setMdc(MDC.create(persistenceId, phaseName))
    this
  }

  private var recoveryTimer: OptionVal[Cancellable] = OptionVal.None

  def startRecoveryTimer(snapshot: Boolean): Unit = {
    cancelRecoveryTimer()
    implicit val ec: ExecutionContext = context.executionContext
    val timer =
      if (snapshot)
        context.system.scheduler
          .scheduleOnce(settings.recoveryEventTimeout, context.self.toUntyped, RecoveryTickEvent(snapshot = true))
      else
        context.system.scheduler.schedule(
          settings.recoveryEventTimeout,
          settings.recoveryEventTimeout,
          context.self.toUntyped,
          RecoveryTickEvent(snapshot = false))
    recoveryTimer = OptionVal.Some(timer)
  }

  def cancelRecoveryTimer(): Unit = {
    recoveryTimer match {
      case OptionVal.Some(t) => t.cancel()
      case OptionVal.None    =>
    }
    recoveryTimer = OptionVal.None
  }

  def onSignal(signal: Signal): Unit = {
    signalHandler.applyOrElse(signal, ConstantFun.scalaAnyToUnit)
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object MDC {
  // format: OFF
  val AwaitingPermit    = "get-permit"
  val ReplayingSnapshot = "replay-snap"
  val ReplayingEvents   = "replay-evts"
  val RunningCmds       = "running-cmnds"
  val PersistingEvents  = "persist-evts"
  val StoringSnapshot  = "storing-snapshot"
  // format: ON

  def create(persistenceId: PersistenceId, phaseName: String): Map[String, Any] = {
    Map("persistenceId" -> persistenceId.id, "phase" -> phaseName)
  }
}
