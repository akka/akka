/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

import akka.actor.typed.Behavior
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.typed.internal.ReplayingEvents.FailureWhileUnstashing

/***
 * INTERNAL API
 *
 * Third (of four) behavior of an EventSourcedBehavior.
 *
 * In this behavior we finally start replaying events, beginning from the last applied sequence number
 * (i.e. the one up-until-which the snapshot recovery has brought us).
 *
 * Once recovery is completed, the actor becomes [[Running]], stashed messages are flushed
 * and control is given to the user's handlers to drive the actors behavior from there.
 *
 * See next behavior [[Running]].
 * See previous behavior [[ReplayingSnapshot]].
 */
@InternalApi
private[akka] object ReplayingEvents {

  @InternalApi
  private[akka] final case class ReplayingState[State](
    seqNr:               Long,
    state:               State,
    eventSeenInInterval: Boolean,
    toSeqNr:             Long,
    receivedPoisonPill:  Boolean
  )

  def apply[C, E, S](
    setup: BehaviorSetup[C, E, S],
    state: ReplayingState[S]
  ): Behavior[InternalProtocol] =
    new ReplayingEvents(setup.setMdc(MDC.ReplayingEvents)).createBehavior(state)

  private final case class FailureWhileUnstashing(cause: Throwable) extends Exception(cause) with NoStackTrace

}

@InternalApi
private[akka] class ReplayingEvents[C, E, S](override val setup: BehaviorSetup[C, E, S])
  extends JournalInteractions[C, E, S] with StashManagement[C, E, S] {
  import InternalProtocol._
  import ReplayingEvents.ReplayingState

  def createBehavior(state: ReplayingState[S]): Behavior[InternalProtocol] = {
    Behaviors.setup { _ ⇒
      // protect against event recovery stalling forever because of journal overloaded and such
      setup.startRecoveryTimer(snapshot = false)

      replayEvents(state.seqNr + 1L, state.toSeqNr)

      stay(state)
    }
  }

  private def stay(state: ReplayingState[S]): Behavior[InternalProtocol] =
    Behaviors.receiveMessage[InternalProtocol] {
      case JournalResponse(r)      ⇒ onJournalResponse(state, r)
      case SnapshotterResponse(r)  ⇒ onSnapshotterResponse(r)
      case RecoveryTickEvent(snap) ⇒ onRecoveryTick(state, snap)
      case cmd: IncomingCommand[C] ⇒ onCommand(cmd, state)
      case RecoveryPermitGranted   ⇒ Behaviors.unhandled // should not happen, we already have the permit
    }.receiveSignal(returnPermitOnStop.orElse {
      case (_, PoisonPill) ⇒ stay(state.copy(receivedPoisonPill = true))
    })

  private def onJournalResponse(
    state:    ReplayingState[S],
    response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    try {
      response match {
        case ReplayedMessage(repr) ⇒
          val event = setup.eventAdapter.fromJournal(repr.payload.asInstanceOf[setup.eventAdapter.Per])

          try {
            val newState = state.copy(
              seqNr = repr.sequenceNr,
              state = setup.eventHandler(state.state, event),
              eventSeenInInterval = true)
            stay(newState)
          } catch {
            case NonFatal(ex) ⇒ onRecoveryFailure(ex, repr.sequenceNr, Some(event))
          }
        case RecoverySuccess(highestSeqNr) ⇒
          setup.log.debug("Recovery successful, recovered until sequenceNr: [{}]", highestSeqNr)
          onRecoveryCompleted(state)

        case ReplayMessagesFailure(cause) ⇒
          onRecoveryFailure(cause, state.seqNr, Some(response))

        case _ ⇒
          Behaviors.unhandled
      }
    } catch {
      case FailureWhileUnstashing(ex) ⇒ throw ex
      case NonFatal(cause) ⇒
        onRecoveryFailure(cause, state.seqNr, None)
    }
  }

  private def onCommand(cmd: InternalProtocol, state: ReplayingState[S]): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    if (state.receivedPoisonPill) {
      if (setup.settings.logOnStashing) setup.log.debug(
        "Discarding message [{}], because actor is to be stopped", cmd)
      Behaviors.unhandled
    } else {
      stashInternal(cmd)
      Behaviors.same
    }
  }

  protected def onRecoveryTick(state: ReplayingState[S], snapshot: Boolean): Behavior[InternalProtocol] =
    if (!snapshot) {
      if (state.eventSeenInInterval) {
        stay(state.copy(eventSeenInInterval = false))
      } else {
        val msg = s"Replay timed out, didn't get event within ]${setup.settings.recoveryEventTimeout}], highest sequence number seen [${state.seqNr}]"
        onRecoveryFailure(new RecoveryTimedOut(msg), state.seqNr, None)
      }
    } else {
      // snapshot timeout, but we're already in the events recovery phase
      Behavior.unhandled
    }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[InternalProtocol] = {
    setup.log.warning("Unexpected [{}] from SnapshotStore, already in replaying events state.", Logging.simpleName(response))
    Behaviors.unhandled // ignore the response
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param message the message that was being processed when the exception was thrown
   */
  protected def onRecoveryFailure(cause: Throwable, sequenceNr: Long, message: Option[Any]): Behavior[InternalProtocol] = {
    try {
      setup.onRecoveryFailure(cause)
    } catch {
      case NonFatal(t) ⇒ setup.log.error(t, "onRecoveryFailure threw exception")
    }
    setup.cancelRecoveryTimer()
    tryReturnRecoveryPermit("on replay failure: " + cause.getMessage)

    val msg = message match {
      case Some(evt) ⇒
        s"Exception during recovery while handling [${evt.getClass.getName}] with sequence number [$sequenceNr]. " +
          s"PersistenceId [${setup.persistenceId.id}]"
      case None ⇒
        s"Exception during recovery.  Last known sequence number [$sequenceNr]. PersistenceId [${setup.persistenceId.id}]"
    }

    throw new JournalFailureException(msg, cause)
  }

  protected def onRecoveryCompleted(state: ReplayingState[S]): Behavior[InternalProtocol] = try {
    tryReturnRecoveryPermit("replay completed successfully")
    setup.recoveryCompleted(state.state)

    if (state.receivedPoisonPill && isInternalStashEmpty && !isUnstashAllInProgress)
      Behaviors.stopped
    else {
      val running = Running[C, E, S](
        setup,
        Running.RunningState[S](state.seqNr, state.state, state.receivedPoisonPill)
      )

      try {
        tryUnstashOne(running)
      } catch {
        case NonFatal(t) ⇒ throw FailureWhileUnstashing(t)
      }
    }
  } finally {
    setup.cancelRecoveryTimer()
  }

}

