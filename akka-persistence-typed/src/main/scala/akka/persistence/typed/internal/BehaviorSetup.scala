/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.ActorRef
import akka.actor.typed.Signal
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.{ EventAdapter, PersistenceId, SnapshotAdapter }
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.util.ConstantFun
import akka.util.OptionVal
import org.slf4j.Logger
import org.slf4j.MDC

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BehaviorSetup {
  sealed trait SnapshotAfterPersist
  case object NoSnapshot extends SnapshotAfterPersist
  case object SnapshotWithRetention extends SnapshotAfterPersist
  case object SnapshotWithoutRetention extends SnapshotAfterPersist
}

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
    private val signalHandler: PartialFunction[(S, Signal), Unit],
    val tagger: E => Set[String],
    val eventAdapter: EventAdapter[E, Any],
    val snapshotAdapter: SnapshotAdapter[S],
    val snapshotWhen: (S, E, Long) => Boolean,
    val recovery: Recovery,
    val retention: RetentionCriteria,
    var holdingRecoveryPermit: Boolean,
    val settings: EventSourcedSettings,
    val stashState: StashState) {

  import InternalProtocol.RecoveryTickEvent
  import akka.actor.typed.scaladsl.adapter._
  import BehaviorSetup._

  val persistence: Persistence = Persistence(context.system.toClassic)

  val journal: ActorRef = persistence.journalFor(settings.journalPluginId)
  val snapshotStore: ActorRef = persistence.snapshotStoreFor(settings.snapshotPluginId)

  def selfClassic: ActorRef = context.self.toClassic

  private var mdcPhase = PersistenceMdc.Initializing
  def log: Logger = {
    // MDC is cleared (if used) from aroundReceive in ActorAdapter after processing each message,
    // but important to call `context.log` to mark MDC as used
    PersistenceMdc.setMdc(persistenceId, mdcPhase)
    context.log
  }

  def setMdcPhase(phaseName: String): BehaviorSetup[C, E, S] = {
    mdcPhase = phaseName
    this
  }

  private var recoveryTimer: OptionVal[Cancellable] = OptionVal.None

  def startRecoveryTimer(snapshot: Boolean): Unit = {
    cancelRecoveryTimer()
    implicit val ec: ExecutionContext = context.executionContext
    val timer =
      if (snapshot)
        context.scheduleOnce(settings.recoveryEventTimeout, context.self, RecoveryTickEvent(snapshot = true))
      else
        context.system.scheduler.scheduleWithFixedDelay(settings.recoveryEventTimeout, settings.recoveryEventTimeout) {
          () =>
            context.self ! RecoveryTickEvent(snapshot = false)
        }
    recoveryTimer = OptionVal.Some(timer)
  }

  def cancelRecoveryTimer(): Unit = {
    recoveryTimer match {
      case OptionVal.Some(t) => t.cancel()
      case OptionVal.None    =>
    }
    recoveryTimer = OptionVal.None
  }

  /**
   * `catchAndLog=true` should be used for "unknown" signals in the phases before Running
   * to avoid restart loops if restart supervision is used.
   */
  def onSignal(state: S, signal: Signal, catchAndLog: Boolean): Unit = {
    try {
      signalHandler.applyOrElse((state, signal), ConstantFun.scalaAnyToUnit)
    } catch {
      case NonFatal(ex) =>
        if (catchAndLog)
          log.error(s"Error while processing signal [$signal]: $ex", ex)
        else {
          if (log.isDebugEnabled)
            log.debug(s"Error while processing signal [$signal]: $ex", ex)
          throw ex
        }
    }
  }

  def shouldSnapshot(state: S, event: E, sequenceNr: Long): SnapshotAfterPersist = {
    retention match {
      case DisabledRetentionCriteria =>
        if (snapshotWhen(state, event, sequenceNr)) SnapshotWithoutRetention
        else NoSnapshot
      case s: SnapshotCountRetentionCriteriaImpl =>
        if (s.snapshotWhen(sequenceNr)) SnapshotWithRetention
        else if (snapshotWhen(state, event, sequenceNr)) SnapshotWithoutRetention
        else NoSnapshot
    }
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object PersistenceMdc {
  // format: OFF
  val Initializing      = "initializing"
  val AwaitingPermit    = "get-permit"
  val ReplayingSnapshot = "load-snap"
  val ReplayingEvents   = "replay-evt"
  val RunningCmds       = "running-cmd"
  val PersistingEvents  = "persist-evt"
  val StoringSnapshot   = "storing-snap"
  // format: ON

  val PersistencePhaseKey = "persistencePhase"
  val PersistenceIdKey = "persistenceId"

  // MDC is cleared (if used) from aroundReceive in ActorAdapter after processing each message,
  // but important to call `context.log` to mark MDC as used
  def setMdc(persistenceId: PersistenceId, phase: String): Unit = {
    val mdcAdpater = MDC.getMDCAdapter
    mdcAdpater.put(PersistenceIdKey, persistenceId.id)
    mdcAdpater.put(PersistencePhaseKey, phase)
  }

}
