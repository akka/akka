/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.persistence._
import akka.persistence.SnapshotProtocol.LoadSnapshotFailed
import akka.persistence.SnapshotProtocol.LoadSnapshotResult
import akka.persistence.typed.RecoveryFailed
import akka.persistence.typed.internal.EventSourcedBehaviorImpl.GetState
import akka.util.unused

/**
 * INTERNAL API
 *
 * Second (of four) behavior of an EventSourcedBehavior.
 *
 * In this behavior the recovery process is initiated.
 * We try to obtain a snapshot from the configured snapshot store,
 * and if it exists, we use it instead of the initial `emptyState`.
 *
 * Once snapshot recovery is done (or no snapshot was selected),
 * recovery of events continues in [[ReplayingEvents]].
 *
 * See next behavior [[ReplayingEvents]].
 * See previous behavior [[RequestingRecoveryPermit]].
 */
@InternalApi
private[akka] object ReplayingSnapshot {

  def apply[C, E, S](setup: BehaviorSetup[C, E, S], receivedPoisonPill: Boolean): Behavior[InternalProtocol] =
    new ReplayingSnapshot(setup.setMdcPhase(PersistenceMdc.ReplayingSnapshot)).createBehavior(receivedPoisonPill)

}

@InternalApi
private[akka] class ReplayingSnapshot[C, E, S](override val setup: BehaviorSetup[C, E, S])
    extends JournalInteractions[C, E, S]
    with SnapshotInteractions[C, E, S]
    with StashManagement[C, E, S] {

  import InternalProtocol._

  onRecoveryStart(setup.context)

  def createBehavior(receivedPoisonPillInPreviousPhase: Boolean): Behavior[InternalProtocol] = {
    // protect against snapshot stalling forever because of journal overloaded and such
    setup.startRecoveryTimer(snapshot = true)

    loadSnapshot(setup.recovery.fromSnapshot, setup.recovery.toSequenceNr)

    def stay(receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
      Behaviors
        .receiveMessage[InternalProtocol] {
          case SnapshotterResponse(r)      => onSnapshotterResponse(r, receivedPoisonPill)
          case JournalResponse(r)          => onJournalResponse(r)
          case RecoveryTickEvent(snapshot) => onRecoveryTick(snapshot)
          case cmd: IncomingCommand[C] =>
            if (receivedPoisonPill) {
              if (setup.settings.logOnStashing)
                setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
              Behaviors.unhandled
            } else
              onCommand(cmd)
          case get: GetState[S @unchecked] => stashInternal(get)
          case RecoveryPermitGranted       => Behaviors.unhandled // should not happen, we already have the permit
        }
        .receiveSignal(returnPermitOnStop.orElse {
          case (_, PoisonPill) =>
            stay(receivedPoisonPill = true)
          case (_, signal) =>
            if (setup.onSignal(setup.emptyState, signal, catchAndLog = true)) Behaviors.same
            else Behaviors.unhandled
        })
    }
    stay(receivedPoisonPillInPreviousPhase)
  }

  /**
   * Called whenever snapshot recovery fails.
   *
   * This method throws `JournalFailureException` which will be caught by the internal
   * supervision strategy to stop or restart the actor with backoff.
   *
   * @param cause failure cause.
   */
  private def onRecoveryFailure(cause: Throwable): Behavior[InternalProtocol] = {
    onRecoveryFailed(setup.context, cause)
    setup.onSignal(setup.emptyState, RecoveryFailed(cause), catchAndLog = true)
    setup.cancelRecoveryTimer()

    tryReturnRecoveryPermit("on snapshot recovery failure: " + cause.getMessage)

    if (setup.log.isDebugEnabled)
      setup.log.debug("Recovery failure for persistenceId [{}]", setup.persistenceId)

    val msg = s"Exception during recovery from snapshot. " +
      s"PersistenceId [${setup.persistenceId.id}]. ${cause.getMessage}"
    throw new JournalFailureException(msg, cause)
  }

  @InternalStableApi
  def onRecoveryStart(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryFailed(@unused context: ActorContext[_], @unused reason: Throwable): Unit = ()

  private def onRecoveryTick(snapshot: Boolean): Behavior[InternalProtocol] =
    if (snapshot) {
      // we know we're in snapshotting mode; snapshot recovery timeout arrived
      val ex = new RecoveryTimedOut(
        s"Recovery timed out, didn't get snapshot within ${setup.settings.recoveryEventTimeout}")
      onRecoveryFailure(ex)
    } else Behaviors.same // ignore, since we received the snapshot already

  def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    stashInternal(cmd)
  }

  def onJournalResponse(response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    setup.log.debug(
      "Unexpected response from journal: [{}], may be due to an actor restart, ignoring...",
      response.getClass.getName)
    Behaviors.unhandled
  }

  def onSnapshotterResponse(
      response: SnapshotProtocol.Response,
      receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
    response match {
      case LoadSnapshotResult(sso, toSnr) =>
        var state: S = setup.emptyState

        val seqNr: Long = sso match {
          case Some(SelectedSnapshot(metadata, snapshot)) =>
            state = setup.snapshotAdapter.fromJournal(snapshot)
            metadata.sequenceNr
          case None => 0 // from the beginning please
        }

        becomeReplayingEvents(state, seqNr, toSnr, receivedPoisonPill)

      case LoadSnapshotFailed(cause) =>
        onRecoveryFailure(cause)

      case _ =>
        Behaviors.unhandled
    }
  }

  private def becomeReplayingEvents(
      state: S,
      lastSequenceNr: Long,
      toSnr: Long,
      receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
    setup.cancelRecoveryTimer()

    ReplayingEvents[C, E, S](
      setup,
      ReplayingEvents.ReplayingState(
        lastSequenceNr,
        state,
        eventSeenInInterval = false,
        toSnr,
        receivedPoisonPill,
        System.nanoTime()))
  }

}
