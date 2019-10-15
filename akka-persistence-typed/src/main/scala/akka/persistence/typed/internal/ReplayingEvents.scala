/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.util.control.NonFatal
import scala.concurrent.duration._

import akka.actor.typed.{ Behavior, Signal }
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.internal.UnstashException
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors, LoggerOps }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.event.Logging
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.typed.EmptyEventSeq
import akka.persistence.typed.EventsSeq
import akka.persistence.typed.RecoveryFailed
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.SingleEventSeq
import akka.persistence.typed.internal.ReplayingEvents.ReplayingState
import akka.persistence.typed.internal.Running.WithSeqNrAccessible
import akka.util.OptionVal
import akka.util.unused
import akka.util.PrettyDuration._

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
      seqNr: Long,
      state: State,
      eventSeenInInterval: Boolean,
      toSeqNr: Long,
      receivedPoisonPill: Boolean,
      recoveryStartTime: Long)

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

  replayEvents(state.seqNr + 1L, state.toSeqNr)
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
      case JournalResponse(r)      => onJournalResponse(r)
      case SnapshotterResponse(r)  => onSnapshotterResponse(r)
      case RecoveryTickEvent(snap) => onRecoveryTick(snap)
      case cmd: IncomingCommand[C] => onCommand(cmd)
      case RecoveryPermitGranted   => Behaviors.unhandled // should not happen, we already have the permit
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
    case PoisonPill =>
      state = state.copy(receivedPoisonPill = true)
      this
    case signal =>
      setup.onSignal(state.state, signal, catchAndLog = true)
      this
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
              state = state.copy(
                seqNr = repr.sequenceNr,
                state = setup.eventHandler(state.state, event),
                eventSeenInInterval = true)
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
          val highestSeqNr = Math.max(highestJournalSeqNr, state.seqNr)
          state = state.copy(seqNr = highestSeqNr)
          setup.log.debug("Recovery successful, recovered until sequenceNr: [{}]", highestSeqNr)
          onRecoveryCompleted(state)

        case ReplayMessagesFailure(cause) =>
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
      Behaviors.same
    }
  }

  protected def onRecoveryTick(snapshot: Boolean): Behavior[InternalProtocol] =
    if (!snapshot) {
      if (state.eventSeenInInterval) {
        state = state.copy(eventSeenInInterval = false)
        this
      } else {
        val msg =
          s"Replay timed out, didn't get event within [${setup.settings.recoveryEventTimeout}], highest sequence number seen [${state.seqNr}]"
        onRecoveryFailure(new RecoveryTimedOut(msg), None)
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
    val sequenceNr = state.seqNr

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
          Running[C, E, S](setup, Running.RunningState[S](state.seqNr, state.state, state.receivedPoisonPill))

        tryUnstashOne(running)
      }
    } finally {
      setup.cancelRecoveryTimer()
    }

  override def currentSequenceNumber: Long = state.seqNr
}
