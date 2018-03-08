/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence.SnapshotProtocol.{ LoadSnapshot, LoadSnapshotFailed, LoadSnapshotResult }
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.EventsourcedProtocol
import akka.util.Helpers._

import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 *
 * Second (of four) behavior of an PersistentBehavior.
 *
 * In this behavior the recovery process is initiated.
 * We try to obtain a snapshot from the configured snapshot store,
 * and if it exists, we use it instead of the `initialState`.
 *
 * Once snapshot recovery is done (or no snapshot was selected),
 * recovery of events continues in [[EventsourcedRecoveringEvents]].
 */
@InternalApi
final class EventsourcedRecoveringSnapshot[C, E, S](
  val setup:                  EventsourcedSetup[C, E, S],
  override val context:       ActorContext[EventsourcedProtocol],
  override val timers:        TimerScheduler[EventsourcedProtocol],
  override val internalStash: StashBuffer[EventsourcedProtocol]
) extends MutableBehavior[EventsourcedProtocol]
  with EventsourcedBehavior[C, E, S]
  with EventsourcedStashManagement {
  import Behaviors.same
  import EventsourcedBehavior._

  override protected def log = context.log

  // -------- initialize --------
  startRecoveryTimer()

  loadSnapshot(persistenceId, setup.recovery.fromSnapshot, setup.recovery.toSequenceNr)
  // ---- end of initialize ----

  val commandContext: ActorContext[C] = context.asInstanceOf[ActorContext[C]]

  // ----------

  protected var awaitingSnapshot: Boolean = true

  // ----------

  private var lastSequenceNr: Long = 0L
  def snapshotSequenceNr: Long = lastSequenceNr

  // ----------

  lazy val timeout = extension.journalConfigFor(journalPluginId).getMillisDuration("recovery-event-timeout")

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "recovery-tick"
  private def startRecoveryTimer(): Unit = {
    timers.startPeriodicTimer(RecoveryTickTimerKey, EventsourcedProtocol.RecoveryTickEvent(snapshot = false), timeout)
  }
  private def cancelRecoveryTimer(): Unit = timers.cancel(RecoveryTickTimerKey)

  def onCommand(cmd: EventsourcedProtocol.IncomingCommand[C]): Behavior[EventsourcedProtocol] = {
    // during recovery, stash all incoming commands
    stash(context, cmd)
    Behavior.same
  }

  def onJournalResponse(response: JournalProtocol.Response): Behavior[EventsourcedProtocol] = try {
    throw new Exception("Should not talk to journal yet! But got: " + response)
  } catch {
    case NonFatal(cause) ⇒
      returnRecoveryPermitOnlyOnFailure(cause)
      throw cause
  }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[EventsourcedProtocol] = try {
    response match {
      case LoadSnapshotResult(sso, toSnr) ⇒
        var state: S = initialState
        val re: Try[Long] = Try {
          sso match {
            case Some(SelectedSnapshot(metadata, snapshot)) ⇒
              state = snapshot.asInstanceOf[S]
              metadata.sequenceNr

            case None ⇒
              0 // from the start please
          }
        }

        re match {
          case Success(seqNr) ⇒
            lastSequenceNr = seqNr
            replayMessages(state, toSnr)

          case Failure(cause) ⇒
            // FIXME better exception type
            val ex = new RuntimeException(s"Failed to recover state for [$persistenceId] from snapshot offer.", cause)
            onRecoveryFailure(ex, event = None) // FIXME the failure logs has bad messages... FIXME
        }

      case LoadSnapshotFailed(cause) ⇒
        cancelRecoveryTimer()

        onRecoveryFailure(cause, event = None)

      case other ⇒
        //        stash(context, other)
        //        same
        Behaviors.unhandled
    }
  } catch {
    case NonFatal(cause) ⇒
      returnRecoveryPermitOnlyOnFailure(cause)
      throw cause
  }

  private def replayMessages(state: S, toSnr: Long): Behavior[EventsourcedProtocol] = {
    cancelRecoveryTimer()

    val rec = setup.recovery.copy(toSequenceNr = toSnr, fromSnapshot = SnapshotSelectionCriteria.None) // TODO introduce new types

    EventsourcedBehavior.withMDC(persistenceId, EventsourcedBehavior.PhaseName.RecoverEvents) {
      new EventsourcedRecoveringEvents[C, E, S](
        setup.copy(recovery = rec),
        context,
        timers,
        internalStash,

        lastSequenceNr,

        state
      )
    }
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param event the event that was processed in `receiveRecover`, if the exception was thrown there
   */
  protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Behavior[EventsourcedProtocol] = {
    cancelRecoveryTimer()
    event match {
      case Some(evt) ⇒
        log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}]", evt.getClass.getName, lastSequenceNr)
        Behaviors.stopped

      case None ⇒
        log.error(cause, "Persistence failure when replaying events; Last known sequence number [{}]", lastSequenceNr)
        Behaviors.stopped
    }
  }

  protected def onRecoveryTick(snapshot: Boolean): Behavior[EventsourcedProtocol] =
    // we know we're in snapshotting mode
    if (snapshot) onRecoveryFailure(new RecoveryTimedOut(s"Recovery timed out, didn't get snapshot within $timeout"), event = None)
    else same // ignore, since we received the snapshot already

  // ----------

  override def onMessage(msg: EventsourcedProtocol): Behavior[EventsourcedProtocol] =
    msg match {
      case EventsourcedProtocol.SnapshotterResponse(r)      ⇒ onSnapshotterResponse(r)
      case EventsourcedProtocol.JournalResponse(r)          ⇒ onJournalResponse(r)
      case EventsourcedProtocol.RecoveryTickEvent(snapshot) ⇒ onRecoveryTick(snapshot = snapshot)
      case in: EventsourcedProtocol.IncomingCommand[C]      ⇒ onCommand(in) // explicit cast to fail eagerly
    }

  // ----------

  // ---------- journal interactions ---------

  /**
   * Instructs the snapshot store to load the specified snapshot and send it via an [[SnapshotOffer]]
   * to the running [[PersistentActor]].
   */
  private def loadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long): Unit = {
    snapshotStore.tell(LoadSnapshot(persistenceId, criteria, toSequenceNr), selfUntyped /*Adapted*/ )
  }

  private def returnRecoveryPermitOnlyOnFailure(cause: Throwable): Unit = {
    log.debug("Returning recovery permit, on failure because: " + cause.getMessage)
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    extension.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRecoveringSnapshot($persistenceId)"

}
