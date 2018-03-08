/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.Behaviors.same
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.actor.typed.{ Behavior, PostStop, Signal }
import akka.annotation.InternalApi
import akka.persistence.SnapshotProtocol.{ LoadSnapshotFailed, LoadSnapshotResult }
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol._
import akka.persistence.typed.internal.EventsourcedBehavior._

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
 *
 * See next behavior [[EventsourcedRecoveringEvents]].
 * See previous behavior [[EventsourcedRequestingRecoveryPermit]].
 */
@InternalApi
private[akka] object EventsourcedRecoveringSnapshot {

  def apply[C, E, S](setup: EventsourcedSetup[C, E, S]): Behavior[InternalProtocol] =
    new EventsourcedRecoveringSnapshot(setup).createBehavior()

}

@InternalApi
private[akka] class EventsourcedRecoveringSnapshot[C, E, S](override val setup: EventsourcedSetup[C, E, S])
  extends EventsourcedJournalInteractions[C, E, S] with EventsourcedStashManagement[C, E, S] {

  def createBehavior(): Behavior[InternalProtocol] = {
    startRecoveryTimer()

    loadSnapshot(setup.recovery.fromSnapshot, setup.recovery.toSequenceNr)

    withMdc(setup, MDC.RecoveringSnapshot) {
      Behaviors.immutable[InternalProtocol] {
        case (_, SnapshotterResponse(r))      ⇒ onSnapshotterResponse(r)
        case (_, JournalResponse(r))          ⇒ onJournalResponse(r)
        case (_, RecoveryTickEvent(snapshot)) ⇒ onRecoveryTick(snapshot)
        case (_, cmd: IncomingCommand[C])     ⇒ onCommand(cmd)
      }.onSignal(returnPermitOnStop)
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
  private def onRecoveryFailure(cause: Throwable, event: Option[Any]): Behavior[InternalProtocol] = {
    cancelRecoveryTimer(setup.timers)

    event match {
      case Some(evt) ⇒
        setup.log.error(cause, "Exception in receiveRecover when replaying snapshot [{}]", evt.getClass.getName)
      case _ ⇒
        setup.log.error(cause, "Persistence failure when replaying snapshot")
    }

    Behaviors.stopped
  }

  private def onRecoveryTick(snapshot: Boolean): Behavior[InternalProtocol] =
    if (snapshot) {
      // we know we're in snapshotting mode; snapshot recovery timeout arrived
      val ex = new RecoveryTimedOut(s"Recovery timed out, didn't get snapshot within ${setup.settings.recoveryEventTimeout}")
      onRecoveryFailure(ex, None)
    } else same // ignore, since we received the snapshot already

  def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    setup.internalStash.stash(cmd)
    Behavior.same
  }

  def onJournalResponse(response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    setup.log.debug("Unexpected response from journal: [{}], may be due to an actor restart, ignoring...", response)
    Behaviors.unhandled
  }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[InternalProtocol] = {
    response match {
      case LoadSnapshotResult(sso, toSnr) ⇒
        var state: S = setup.initialState

        val seqNr: Long = sso match {
          case Some(SelectedSnapshot(metadata, snapshot)) ⇒
            state = snapshot.asInstanceOf[S]
            metadata.sequenceNr
          case None ⇒ 0 // from the beginning please
        }

        becomeReplayingEvents(state, seqNr, toSnr)

      case LoadSnapshotFailed(cause) ⇒
        cancelRecoveryTimer(setup.timers)
        onRecoveryFailure(cause, event = None)

      case _ ⇒
        Behaviors.unhandled
    }
  }

  private def becomeReplayingEvents(state: S, lastSequenceNr: Long, toSnr: Long): Behavior[InternalProtocol] = {
    cancelRecoveryTimer(setup.timers)

    val rec = setup.recovery.copy(
      toSequenceNr = toSnr,
      fromSnapshot = SnapshotSelectionCriteria.None
    )

    EventsourcedRecoveringEvents[C, E, S](
      setup.copy(recovery = rec),
      // setup.internalStash, // TODO move it out of setup
      EventsourcedRecoveringEvents.RecoveringState(lastSequenceNr, state)
    )
  }

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "snapshot-recovery-tick"
  private def startRecoveryTimer(): Unit =
    setup.timers.startPeriodicTimer(RecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), setup.settings.recoveryEventTimeout)
  private def cancelRecoveryTimer(timers: TimerScheduler[_]): Unit = timers.cancel(RecoveryTickTimerKey)

}
