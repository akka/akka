/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.actor.typed.{ Behavior, Signal }
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.internal.UnstashException
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors, LoggerOps }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.event.Logging
import akka.persistence._
import akka.persistence.JournalProtocol._
import akka.persistence.typed.EmptyEventSeq
import akka.persistence.typed.EventsSeq
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.RecoveryFailed
import akka.persistence.typed.SingleEventSeq
import akka.persistence.typed.internal.EventSourcedBehaviorImpl.GetState
import akka.persistence.typed.internal.ReplayingEvents.ReplayingState
import akka.persistence.typed.internal.Running.WithSeqNrAccessible
import akka.util.OptionVal
import akka.util.PrettyDuration._
import akka.util.unused

import scala.collection.immutable.SortedMap
import scala.collection.mutable

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
      eventSeqNr: Long,
      state: State,
      eventSeenInInterval: Boolean,
      toSeqNr: Long,
      receivedPoisonPill: Boolean,
      recoveryStartTime: Long,
      idempotencyKeyCache: SortedMap[Long, String],
      idempotencyKeySeqNr: Long,
      idempotencyKeySeenInInterval: Boolean,
      eventReplayDone: Boolean,
      idempotencyRestoreDone: Boolean) {

    lazy val readyForRunning: Boolean = eventReplayDone && idempotencyRestoreDone
  }

  def apply[C, E, S](setup: BehaviorSetup[C, E, S], state: ReplayingState[S]): Behavior[InternalProtocol] =
    Behaviors.setup { _ =>
      // protect against event recovery stalling forever because of journal overloaded and such
      setup.startRecoveryTimer(snapshot = false)
      new ReplayingEvents[C, E, S](setup.setMdcPhase(PersistenceMdc.ReplayingEvents), state)
    }

}

@InternalApi
private[akka] final class ReplayingEvents[C, E, S](
    override val setup: BehaviorSetup[C, E, S],
    var state: ReplayingState[S])
    extends AbstractBehavior[InternalProtocol](setup.context)
    with JournalInteractions[C, E, S]
    with SnapshotInteractions[C, E, S]
    with StashManagement[C, E, S]
    with WithSeqNrAccessible {

  import InternalProtocol._
  import ReplayingEvents.ReplayingState

  replayEvents(state.eventSeqNr + 1L, state.toSeqNr)
  internalRestoreIdempotency()
  onRecoveryStart(setup.context)

  @InternalStableApi
  def onRecoveryStart(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryComplete(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryFailed(@unused context: ActorContext[_], @unused reason: Throwable, @unused event: Option[Any]): Unit =
    ()

  override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = {
    msg match {
      case JournalResponse(r)          => onJournalResponse(r)
      case SnapshotterResponse(r)      => onSnapshotterResponse(r)
      case RecoveryTickEvent(snap)     => onRecoveryTick(snap)
      case cmd: IncomingCommand[C]     => onCommand(cmd)
      case get: GetState[S @unchecked] => stashInternal(get)
      case RecoveryPermitGranted       => Behaviors.unhandled // should not happen, we already have the permit
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
    case PoisonPill =>
      state = state.copy(receivedPoisonPill = true)
      this
    case signal =>
      if (setup.onSignal(state.state, signal, catchAndLog = true)) this
      else Behaviors.unhandled
  }

  private def onJournalResponse(response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    try {
      response match {
        case ReplayedMessage(repr) =>
          var eventForErrorReporting: OptionVal[Any] = OptionVal.None
          try {
            val eventSeq = setup.eventAdapter.fromJournal(repr.payload, repr.manifest)

            def handleEvent(event: E): Unit = {
              eventForErrorReporting = OptionVal.Some(event)
              state = state.copy(eventSeqNr = repr.sequenceNr)
              state = state.copy(state = setup.eventHandler(state.state, event), eventSeenInInterval = true)
            }

            eventSeq match {
              case SingleEventSeq(event) => handleEvent(event)
              case EventsSeq(events)     => events.foreach(handleEvent)
              case EmptyEventSeq         => // no events
            }

            this
          } catch {
            case NonFatal(ex) =>
              state = state.copy(repr.sequenceNr)
              onRecoveryFailure(ex, eventForErrorReporting.toOption)
          }

        case RecoverySuccess(highestJournalSeqNr) =>
          val highestSeqNr = Math.max(highestJournalSeqNr, state.eventSeqNr)
          state = state.copy(eventSeqNr = highestSeqNr, eventReplayDone = true)
          setup.log.debug("Event replay successful, recovered until sequenceNr: [{}]", highestSeqNr)

          if (state.readyForRunning) {
            onRecoveryCompleted(state)
          } else {
            this
          }

        case ReplayMessagesFailure(cause) =>
          onRecoveryFailure(cause, Some(response))

        case RestoredIdempotencyKey(key, sequenceNr) =>
          if (state.idempotencyKeyCache.size == setup.idempotencyKeyCacheSize) {
            onRecoveryFailure(
              new IllegalArgumentException(
                s"Journal attempted to restore too many idempotence keys, " +
                s"key [$key], " +
                s"sequenceNr [$sequenceNr]"),
              Some(response))
          } else {
            state = state.copy(
              idempotencyKeyCache = state.idempotencyKeyCache.updated(sequenceNr, key),
              idempotencyKeySeqNr = sequenceNr)
            this
          }

        case RestoreIdempotencySuccess(highestSequenceNr) =>
          state = state.copy(idempotencyKeySeqNr = highestSequenceNr, idempotencyRestoreDone = true)
          setup.log.debug(
            "Idempotence restore successful, " +
            "recovered until sequenceNr: [{}], " +
            "total restored idempotency keys [{}]",
            highestSequenceNr,
            state.idempotencyKeyCache.size)

          if (state.readyForRunning) {
            onRecoveryCompleted(state)
          } else {
            this
          }

        case RestoreIdempotencyFailure(cause) =>
          onRecoveryFailure(cause, Some(response))

        case _ =>
          Behaviors.unhandled
      }
    } catch {
      case ex: UnstashException[_] =>
        // let supervisor handle it, don't treat it as recovery failure
        throw ex
      case NonFatal(cause) =>
        onRecoveryFailure(cause, None)
    }
  }

  private def onCommand(cmd: InternalProtocol): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    if (state.receivedPoisonPill) {
      if (setup.settings.logOnStashing)
        setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
      Behaviors.unhandled
    } else {
      stashInternal(cmd)
    }
  }

  protected def onRecoveryTick(snapshot: Boolean): Behavior[InternalProtocol] =
    if (!snapshot) {
      val eventState = if (!state.eventReplayDone) {
        if (state.eventSeenInInterval) {
          Right((state: ReplayingState[S]) => state.copy(eventSeenInInterval = false))
        } else {
          Left(
            s"didn't get event within [${setup.settings.recoveryEventTimeout}], highest sequence number seen [${state.eventSeqNr}]")
        }
      } else {
        Right((state: ReplayingState[S]) => state)
      }

      val idempotencyKeyState = if (!state.idempotencyRestoreDone) {
        if (state.idempotencyKeySeenInInterval) {
          Right((state: ReplayingState[S]) => state.copy(idempotencyKeySeenInInterval = false))
        } else {
          Left(
            s"didn't get idempotency key within [${setup.settings.recoveryEventTimeout}], highest sequence number seen [${state.idempotencyKeySeqNr}]")
        }
      } else {
        Right((state: ReplayingState[S]) => state)
      }

      val status = (eventState, idempotencyKeyState) match {
        case (Right(e), Right(ik)) =>
          Right(ik(e(state)))
        case (e, ik) =>
          val errs = Seq(e, ik)
            .collect {
              case Left(err) =>
                err
            }
            .mkString(", ")
          Left(s"Replay timed out$errs")
      }

      status match {
        case Right(s) =>
          state = s
          this
        case Left(err) =>
          onRecoveryFailure(new RecoveryTimedOut(err), None)
      }
    } else {
      // snapshot timeout, but we're already in the events recovery phase
      Behaviors.unhandled
    }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[InternalProtocol] = {
    setup.log
      .warn("Unexpected [{}] from SnapshotStore, already in replaying events state.", Logging.simpleName(response))
    Behaviors.unhandled // ignore the response
  }

  /**
   * Called whenever a message replay fails.
   *
   * This method throws `JournalFailureException` which will be caught by the internal
   * supervision strategy to stop or restart the actor with backoff.
   *
   * @param cause failure cause.
   * @param event the event that was being processed when the exception was thrown
   */
  private def onRecoveryFailure(cause: Throwable, event: Option[Any]): Behavior[InternalProtocol] = {
    onRecoveryFailed(setup.context, cause, event)
    setup.onSignal(state.state, RecoveryFailed(cause), catchAndLog = true)
    setup.cancelRecoveryTimer()
    tryReturnRecoveryPermit("on replay failure: " + cause.getMessage)
    if (setup.log.isDebugEnabled) {
      setup.log.debug2(
        "Recovery failure for persistenceId [{}] after {}",
        setup.persistenceId,
        (System.nanoTime() - state.recoveryStartTime).nanos.pretty)
    }
    val sequenceNr = state.eventSeqNr

    val msg = event match {
      case Some(_: Message) | None =>
        s"Exception during recovery. Last known sequence number [$sequenceNr]. " +
        s"PersistenceId [${setup.persistenceId.id}], due to: ${cause.getMessage}"
      case Some(evt) =>
        s"Exception during recovery while handling [${evt.getClass.getName}] with sequence number [$sequenceNr]. " +
        s"PersistenceId [${setup.persistenceId.id}], due to: ${cause.getMessage}"
    }

    throw new JournalFailureException(msg, cause)
  }

  private def onRecoveryCompleted(state: ReplayingState[S]): Behavior[InternalProtocol] =
    try {
      onRecoveryComplete(setup.context)
      tryReturnRecoveryPermit("replay completed successfully")
      if (setup.log.isDebugEnabled) {
        setup.log.debug2(
          "Recovery for persistenceId [{}] took {}",
          setup.persistenceId,
          (System.nanoTime() - state.recoveryStartTime).nanos.pretty)
      }

      setup.onSignal(state.state, RecoveryCompleted, catchAndLog = false)

      if (state.receivedPoisonPill && isInternalStashEmpty && !isUnstashAllInProgress)
        Behaviors.stopped
      else {
        val running =
          Running[C, E, S](
            setup,
            Running.RunningState[S](
              state.eventSeqNr,
              state.state,
              state.receivedPoisonPill,
              state.idempotencyKeySeqNr,
              if (setup.idempotencyKeyCacheSize == 0) {
                Running.NoCache
              } else {
                Running.LRUCache(
                  mutable.LinkedHashSet.empty[String] ++ state.idempotencyKeyCache.values,
                  setup.idempotencyKeyCacheSize)
              }))

        tryUnstashOne(running)
      }
    } finally {
      setup.cancelRecoveryTimer()
    }

  override def currentEventSequenceNumber: Long =
    state.eventSeqNr
  override def currentIdempotencyKeySequenceNumber: Long = state.idempotencyKeySeqNr
}
