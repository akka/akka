/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ Behaviors, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol._
import akka.persistence.typed.internal.EventsourcedBehavior._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/***
 * INTERNAL API
 *
 * Third (of four) behavior of an PersistentBehavior.
 *
 * In this behavior we finally start replaying events, beginning from the last applied sequence number
 * (i.e. the one up-until-which the snapshot recovery has brought us).
 *
 * Once recovery is completed, the actor becomes [[EventsourcedRunning]], stashed messages are flushed
 * and control is given to the user's handlers to drive the actors behavior from there.
 *
 * See next behavior [[EventsourcedRunning]].
 * See previous behavior [[EventsourcedReplayingSnapshot]].
 */
@InternalApi
private[persistence] object EventsourcedReplayingEvents {

  @InternalApi
  private[persistence] final case class ReplayingState[State](
    seqNr:               Long,
    state:               State,
    eventSeenInInterval: Boolean,
    toSeqNr:             Long
  )

  def apply[C, E, S](
    setup: EventsourcedSetup[C, E, S],
    state: ReplayingState[S]
  ): Behavior[InternalProtocol] =
    new EventsourcedReplayingEvents(setup.setMdc(MDC.ReplayingEvents)).createBehavior(state)

}

@InternalApi
private[persistence] class EventsourcedReplayingEvents[C, E, S](override val setup: EventsourcedSetup[C, E, S])
  extends EventsourcedJournalInteractions[C, E, S] with EventsourcedStashManagement[C, E, S] {
  import EventsourcedReplayingEvents.ReplayingState

  def createBehavior(state: ReplayingState[S]): Behavior[InternalProtocol] = {
    Behaviors.setup { _ ⇒
      startRecoveryTimer(setup.timers, setup.settings.recoveryEventTimeout)

      replayEvents(state.seqNr + 1L, state.toSeqNr)

      stay(state)
    }
  }

  private def stay(state: ReplayingState[S]): Behavior[InternalProtocol] =
    Behaviors.receiveMessage[InternalProtocol] {
      case JournalResponse(r)      ⇒ onJournalResponse(state, r)
      case SnapshotterResponse(r)  ⇒ onSnapshotterResponse(r)
      case RecoveryTickEvent(snap) ⇒ onRecoveryTick(state, snap)
      case cmd: IncomingCommand[C] ⇒ onCommand(cmd)
      case RecoveryPermitGranted   ⇒ Behaviors.unhandled // should not happen, we already have the permit
    }.receiveSignal(returnPermitOnStop)

  private def onJournalResponse(
    state:    ReplayingState[S],
    response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    try {
      response match {
        case ReplayedMessage(repr) ⇒
          val event = repr.payload.asInstanceOf[E]

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
          cancelRecoveryTimer(setup.timers)

          onRecoveryCompleted(state)

        case ReplayMessagesFailure(cause) ⇒
          onRecoveryFailure(cause, state.seqNr, Some(response))

        case _ ⇒
          Behaviors.unhandled
      }
    } catch {
      case NonFatal(cause) ⇒
        onRecoveryFailure(cause, state.seqNr, None)
    }
  }

  private def onCommand(cmd: InternalProtocol): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    stash(cmd)
    Behaviors.same
  }

  protected def onRecoveryTick(state: ReplayingState[S], snapshot: Boolean): Behavior[InternalProtocol] =
    if (!snapshot) {
      if (state.eventSeenInInterval) {
        stay(state.copy(eventSeenInInterval = false))
      } else {
        cancelRecoveryTimer(setup.timers)
        val msg = s"Replay timed out, didn't get event within ]${setup.settings.recoveryEventTimeout}], highest sequence number seen [${state.seqNr}]"
        onRecoveryFailure(new RecoveryTimedOut(msg), state.seqNr, None) // TODO allow users to hook into this?
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
    cancelRecoveryTimer(setup.timers)
    tryReturnRecoveryPermit("on replay failure: " + cause.getMessage)

    message match {
      case Some(evt) ⇒
        setup.log.error(cause, "Exception during recovery while handling [{}] with sequence number [{}].", evt.getClass.getName, sequenceNr)
      case None ⇒
        setup.log.error(cause, "Exception during recovery.  Last known sequence number [{}]", setup.persistenceId, sequenceNr)
    }

    Behaviors.stopped
  }

  protected def onRecoveryCompleted(state: ReplayingState[S]): Behavior[InternalProtocol] = try {
    tryReturnRecoveryPermit("replay completed successfully")
    setup.recoveryCompleted(setup.commandContext, state.state)

    val running = EventsourcedRunning[C, E, S](
      setup,
      EventsourcedRunning.EventsourcedState[S](state.seqNr, state.state)
    )

    tryUnstash(running)
  } finally {
    cancelRecoveryTimer(setup.timers)
  }

  // protect against event recovery stalling forever because of journal overloaded and such
  private val EventRecoveryTickTimerKey = "event-recovery-tick"
  private def startRecoveryTimer(timers: TimerScheduler[InternalProtocol], timeout: FiniteDuration): Unit =
    timers.startPeriodicTimer(EventRecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), timeout)
  private def cancelRecoveryTimer(timers: TimerScheduler[InternalProtocol]): Unit = timers.cancel(EventRecoveryTickTimerKey)

}

