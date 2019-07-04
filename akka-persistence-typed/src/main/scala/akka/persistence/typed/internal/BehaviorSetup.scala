/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import akka.actor.Cancellable
import akka.actor.typed.Logger
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.ActorRef
import akka.actor.typed.Signal
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.util.ConstantFun
import akka.util.OptionVal

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
    val eventAdapter: EventAdapter[E, _],
    val snapshotWhen: (S, E, Long) => Boolean,
    val recovery: Recovery,
    val retention: RetentionCriteria,
    var holdingRecoveryPermit: Boolean,
    val settings: EventSourcedSettings,
    val stashState: StashState) {

  import InternalProtocol.RecoveryTickEvent
  import akka.actor.typed.scaladsl.adapter._
  import BehaviorSetup._

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
          log.error(ex, s"Error while processing signal [{}]", signal)
        else {
          log.debug(s"Error while processing signal [{}]: {}", signal, ex)
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
