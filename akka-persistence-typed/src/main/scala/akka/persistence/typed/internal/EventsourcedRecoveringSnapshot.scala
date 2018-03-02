/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.same
import akka.actor.typed.scaladsl.{ Behaviors, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence.SnapshotProtocol.{ LoadSnapshotFailed, LoadSnapshotResult }
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol._
import akka.{ actor ⇒ a }

import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 *
 * Second (of four) behavior of an PersistentBehavior.
 * See next behavior [[EventsourcedRecoveringEvents]].
 *
 * In this behavior the recovery process is initiated.
 * We try to obtain a snapshot from the configured snapshot store,
 * and if it exists, we use it instead of the `initialState`.
 *
 * Once snapshot recovery is done (or no snapshot was selected),
 * recovery of events continues in [[EventsourcedRecoveringEvents]].
 */
@InternalApi
object EventsourcedRecoveringSnapshot extends EventsourcedJournalInteractions with EventsourcedStashManagement {

  def apply[Command, Event, State](setup: EventsourcedSetup[Command, Event, State]): Behavior[InternalProtocol] = {
    startRecoveryTimer(setup)

    withMdc(setup) {
      Behaviors.immutable {
        case (_, SnapshotterResponse(r))        ⇒ onSnapshotterResponse(setup, r)
        case (_, JournalResponse(r))            ⇒ onJournalResponse(setup, r)
        case (_, RecoveryTickEvent(snapshot))   ⇒ onRecoveryTick(setup, snapshot)
        case (_, cmd: IncomingCommand[Command]) ⇒ onCommand(setup, cmd)
      }
    }
  }

  def withMdc[C, E, S](setup: EventsourcedSetup[C, E, S])(b: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    val mdc = Map(
      "persistenceId" → setup.persistenceId,
      "phase" → "recover-snap"
    )
    Behaviors.withMdc(_ ⇒ mdc, b)
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param event the event that was processed in `receiveRecover`, if the exception was thrown there
   */
  private def onRecoveryFailure[C, E, S](setup: EventsourcedSetup[C, E, S], cause: Throwable, event: Option[Any]): Behavior[InternalProtocol] = {
    cancelRecoveryTimer(setup.timers)

    val lastSequenceNr = 0 // FIXME not needed since snapshot == 0
    event match {
      case Some(evt) ⇒
        setup.log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}] for " +
          "persistenceId [{}].", evt.getClass.getName, lastSequenceNr, setup.persistenceId)
        Behaviors.stopped

      case None ⇒
        setup.log.error(cause, "Persistence failure when replaying events for persistenceId [{}]. " +
          "Last known sequence number [{}]", setup.persistenceId, lastSequenceNr)
        Behaviors.stopped
    }
  }

  private def onRecoveryTick[Command, Event, State](setup: EventsourcedSetup[Command, Event, State], snapshot: Boolean): Behavior[InternalProtocol] =
    if (snapshot) {
      // we know we're in snapshotting mode; snapshot recovery timeout arrived
      val ex = new RecoveryTimedOut(s"Recovery timed out, didn't get snapshot within ${setup.settings.recoveryEventTimeout}")
      onRecoveryFailure(setup, ex, event = None)
    } else same // ignore, since we received the snapshot already

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "recovery-tick"

  private def startRecoveryTimer(setup: EventsourcedSetup[_, _, _]): Unit = {
    setup.timers.startPeriodicTimer(RecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), setup.settings.recoveryEventTimeout)
  }

  private def cancelRecoveryTimer(timers: TimerScheduler[_]): Unit = timers.cancel(RecoveryTickTimerKey)

  def onCommand[C, E, S](setup: EventsourcedSetup[C, E, S], cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    setup.internalStash.stash(cmd) // TODO move stash out as it's mutable
    Behavior.same
  }

  def onJournalResponse[Command](setup: EventsourcedSetup[_, _, _], response: JournalProtocol.Response): Behavior[InternalProtocol] = try {
    throw new Exception("Should not talk to journal yet! But got: " + response)
  } catch {
    case NonFatal(cause) ⇒
      returnRecoveryPermitOnlyOnFailure(setup, cause)
      throw cause
  }

  def onSnapshotterResponse[Command, Event, State](setup: EventsourcedSetup[Command, Event, State], response: SnapshotProtocol.Response): Behavior[InternalProtocol] = try {
    response match {
      case LoadSnapshotResult(sso, toSnr) ⇒
        var state: State = setup.initialState
        val re: Try[Long] = Try {
          sso match {
            case Some(SelectedSnapshot(metadata, snapshot)) ⇒
              state = snapshot.asInstanceOf[State]
              metadata.sequenceNr

            case None ⇒
              0 // from the start please
          }
        }

        re match {
          case Success(seqNr) ⇒
            replayMessages(setup, state, seqNr, toSnr)

          case Failure(cause) ⇒
            // FIXME better exception type
            val ex = new RuntimeException(s"Failed to recover state for [${setup.persistenceId}] from snapshot offer.", cause)
            onRecoveryFailure(setup, ex, event = None) // FIXME the failure logs has bad messages... FIXME
        }

      case LoadSnapshotFailed(cause) ⇒
        cancelRecoveryTimer(setup.timers)

        onRecoveryFailure(setup, cause, event = None)

      case _ ⇒
        Behaviors.unhandled
    }
  } catch {
    case NonFatal(cause) ⇒
      returnRecoveryPermitOnlyOnFailure(setup, cause)
      throw cause
  }

  private def replayMessages[Command, Event, State](setup: EventsourcedSetup[Command, Event, State], state: State, lastSequenceNr: Long, toSnr: Long): Behavior[InternalProtocol] = {
    cancelRecoveryTimer(setup.timers)

    val rec = setup.recovery.copy(toSequenceNr = toSnr, fromSnapshot = SnapshotSelectionCriteria.None) // TODO introduce new types

    EventsourcedRecoveringEvents[Command, Event, State](
      setup.copy(recovery = rec),
      // setup.internalStash, // TODO move it out of setup
      EventsourcedRecoveringEvents.RecoveringState(lastSequenceNr, state)
    )
  }

}
