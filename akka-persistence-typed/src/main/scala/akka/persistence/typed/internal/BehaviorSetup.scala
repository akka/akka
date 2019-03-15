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

/**
  * INTERNAL API
  * Setup snapshot and event delete/retention behavior. Retention bridges snapshot
  * and journal behavior. This defines the retention criteria.
  *
  * @param snapshotEveryNEvents Snapshots are used to reduce playback/recovery times.
  *                             This defines when a new snapshot is persisted.
  *
  * @param keepNSnapshots      After a snapshot is successfully completed,
  *                             - if 2: retain last maximum 2 *`snapshot-size` events
  *                             and 3 snapshots (2 old + latest snapshot)
  *                             - if 0: all events with equal or lower sequence number
  *                             will not be retained.
  *
  * @param deleteEventsOnSnapshot Opt-in ability to delete older events on successful
  *                               save of snapshot. Defaults to disabled.
  */
final case class RetentionCriteria(snapshotEveryNEvents: Long,
                                   keepNSnapshots: Long,
                                   deleteEventsOnSnapshot: Boolean) {
  /**
    * Delete Messages:
    *   {{{ toSequenceNr - keepNSnapshots * snapshotEveryNEvents }}}
    * Delete Snapshots:
    *   {{{ (toSequenceNr - 1) - (keepNSnapshots * snapshotEveryNEvents) }}}
    *
    * @param toSequenceNr the sequence number to delete to if `deleteEventsOnSnapshot` is false
    */
  def toSequenceNumber(lastSequenceNr: Long): Long = {
    // Delete old events, retain the latest
    lastSequenceNr - (keepNSnapshots * snapshotEveryNEvents)
  }
}

object RetentionCriteria {

  def apply(): RetentionCriteria =
    RetentionCriteria(
      snapshotEveryNEvents = 1000L,
      keepNSnapshots = 2L,
      deleteEventsOnSnapshot = false)

  /** Scala API. */
  def apply(snapshotEveryNEvents: Long, keepNSnapshots: Long): RetentionCriteria =
    RetentionCriteria(snapshotEveryNEvents, keepNSnapshots, deleteEventsOnSnapshot = false)

  /** Java API. */
  def create(snapshotEveryNEvents: Long, keepNSnapshots: Long): RetentionCriteria =
    apply(snapshotEveryNEvents, keepNSnapshots)

  /** Java API. */
  def create(snapshotEveryNEvents: Long, keepNSnapshots: Long, deleteMessagesOnSnapshot: Boolean): RetentionCriteria =
    apply(snapshotEveryNEvents, keepNSnapshots, deleteMessagesOnSnapshot)
}