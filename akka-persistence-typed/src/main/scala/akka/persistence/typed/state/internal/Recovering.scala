/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.internal

import scala.concurrent.duration._

import akka.actor.typed.Behavior
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi
import akka.persistence._
import akka.persistence.typed.state.internal.DurableStateBehaviorImpl.GetState
import akka.persistence.typed.state.internal.Running.WithRevisionAccessible
import akka.persistence.state.scaladsl.GetObjectResult
import akka.persistence.typed.state.RecoveryCompleted
import akka.persistence.typed.state.RecoveryFailed
import akka.util.PrettyDuration._
import akka.util.unused

/**
 * INTERNAL API
 *
 * Second (of three) behavior of a `DurableStateBehavior`.
 *
 * In this behavior the recovery process is initiated.
 * We try to obtain the state from the configured `DurableStateStore`,
 * and if it exists, we use it instead of the initial `emptyState`.
 *
 * See next behavior [[Running]].
 * See previous behavior [[RequestingRecoveryPermit]].
 */
@InternalApi
private[akka] object Recovering {

  def apply[C, S](setup: BehaviorSetup[C, S], receivedPoisonPill: Boolean): Behavior[InternalProtocol] =
    new Recovering(setup.setMdcPhase(PersistenceMdc.RecoveringState)).createBehavior(receivedPoisonPill)

  @InternalApi
  private[akka] final case class RecoveryState[State](
      revision: Long,
      state: State,
      receivedPoisonPill: Boolean,
      recoveryStartTime: Long)

}

@InternalApi
private[akka] class Recovering[C, S](override val setup: BehaviorSetup[C, S])
    extends DurableStateStoreInteractions[C, S]
    with StashManagement[C, S]
    with WithRevisionAccessible {

  import InternalProtocol._
  import Recovering.RecoveryState

  // Needed for WithSeqNrAccessible
  private var _currentRevision = 0L

  onRecoveryStart(setup.context)

  def createBehavior(receivedPoisonPillInPreviousPhase: Boolean): Behavior[InternalProtocol] = {
    // protect against store stalling forever because of store overloaded and such
    setup.startRecoveryTimer()

    internalGet(setup.context)

    def stay(receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
      Behaviors
        .receiveMessage[InternalProtocol] {
          case success: GetSuccess[S @unchecked] => onGetSuccess(success.result, receivedPoisonPill)
          case GetFailure(exc)                   => onGetFailure(exc)
          case RecoveryTimeout                   => onRecoveryTimeout()
          case cmd: IncomingCommand[C @unchecked] =>
            if (receivedPoisonPill) {
              if (setup.settings.logOnStashing)
                setup.internalLogger.debug("Discarding message [{}], because actor is to be stopped.", cmd)
              Behaviors.unhandled
            } else
              onCommand(cmd)
          case get: GetState[S @unchecked] => stashInternal(get)
          case RecoveryPermitGranted       => Behaviors.unhandled // should not happen, we already have the permit
          case UpsertSuccess               => Behaviors.unhandled
          case _: UpsertFailure            => Behaviors.unhandled
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
   * Called whenever recovery fails.
   *
   * This method throws `DurableStateStoreException` which will be caught by the internal
   * supervision strategy to stop or restart the actor with backoff.
   *
   * @param cause failure cause.
   */
  private def onRecoveryFailure(cause: Throwable): Behavior[InternalProtocol] = {
    onRecoveryFailed(setup.context, cause)
    setup.onSignal(setup.emptyState, RecoveryFailed(cause), catchAndLog = true)
    setup.cancelRecoveryTimer()

    tryReturnRecoveryPermit("on recovery failure: " + cause.getMessage)

    if (setup.internalLogger.isDebugEnabled)
      setup.internalLogger.debug("Recovery failure for persistenceId [{}]", setup.persistenceId)

    val msg = s"Exception during recovery. " +
      s"PersistenceId [${setup.persistenceId.id}]. ${cause.getMessage}"
    throw new DurableStateStoreException(msg, cause)
  }

  @InternalStableApi
  def onRecoveryStart(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryComplete(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryFailed(@unused context: ActorContext[_], @unused reason: Throwable): Unit = ()

  private def onRecoveryTimeout(): Behavior[InternalProtocol] = {
    val ex = new RecoveryTimedOut(s"Recovery timed out, didn't get state within ${setup.settings.recoveryTimeout}")
    onRecoveryFailure(ex)
  }

  def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    stashInternal(cmd)
  }

  def onGetSuccess(result: GetObjectResult[S], receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
    val state = result.value match {
      case Some(s) => setup.snapshotAdapter.fromJournal(s)
      case None    => setup.emptyState
    }

    setup.internalLogger.debug("Recovered from revision [{}]", result.revision)

    setup.cancelRecoveryTimer()

    onRecoveryCompleted(RecoveryState(result.revision, state, receivedPoisonPill, System.nanoTime()))

  }

  private def onRecoveryCompleted(state: RecoveryState[S]): Behavior[InternalProtocol] =
    try {
      _currentRevision = state.revision
      onRecoveryComplete(setup.context)
      tryReturnRecoveryPermit("recovery completed successfully")
      if (setup.internalLogger.isDebugEnabled) {
        setup.internalLogger.debug2(
          "Recovery for persistenceId [{}] took {}",
          setup.persistenceId,
          (System.nanoTime() - state.recoveryStartTime).nanos.pretty)
      }

      setup.onSignal(state.state, RecoveryCompleted, catchAndLog = false)

      if (state.receivedPoisonPill && isInternalStashEmpty && !isUnstashAllInProgress)
        Behaviors.stopped
      else {
        val runningState = Running.RunningState[S](
          revision = state.revision,
          state = state.state,
          receivedPoisonPill = state.receivedPoisonPill)
        val running = new Running(setup.setMdcPhase(PersistenceMdc.RunningCmds))
        tryUnstashOne(new running.HandlingCommands(runningState))
      }
    } finally {
      setup.cancelRecoveryTimer()
    }

  def onGetFailure(cause: Throwable): Behavior[InternalProtocol] = {
    onRecoveryFailure(cause)
  }

  override def currentRevision: Long =
    _currentRevision

}
