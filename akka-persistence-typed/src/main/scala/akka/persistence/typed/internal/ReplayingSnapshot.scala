/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.persistence.SnapshotProtocol.LoadSnapshotFailed
import akka.persistence.SnapshotProtocol.LoadSnapshotResult
import akka.persistence._
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
    new ReplayingSnapshot(setup.setMdc(MDC.ReplayingSnapshot)).createBehavior(receivedPoisonPill)

}

@InternalApi
private[akka] class ReplayingSnapshot[C, E, S](override val setup: BehaviorSetup[C, E, S])
    extends JournalInteractions[C, E, S]
    with SnapshotInteractions[C, E, S]
    with StashManagement[C, E, S] {

  import InternalProtocol._

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
          case RecoveryPermitGranted => Behaviors.unhandled // should not happen, we already have the permit
        }
        .receiveSignal(returnPermitOnStop.orElse {
          case (_, PoisonPill) =>
            stay(receivedPoisonPill = true)
          case (_, signal) =>
            setup.onSignal(setup.emptyState, signal, catchAndLog = true)
            Behaviors.same
        })
    }
    stay(receivedPoisonPillInPreviousPhase)
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   */
  private def onRecoveryFailure(cause: Throwable): Behavior[InternalProtocol] = {
    onRecoveryFailed(setup.context, cause)
    setup.cancelRecoveryTimer()
    setup.log.error(cause, s"Persistence failure when replaying snapshot. ${cause.getMessage}")
    Behaviors.stopped
  }

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
    Behaviors.same
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
            state = snapshot.asInstanceOf[S]
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
