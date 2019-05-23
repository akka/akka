/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.annotation.tailrec
import scala.collection.immutable
import akka.actor.UnhandledMessage
import akka.actor.typed.Behavior
import akka.actor.typed.Signal
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.persistence.DeleteMessagesFailure
import akka.persistence.DeleteMessagesSuccess
import akka.persistence.DeleteSnapshotFailure
import akka.persistence.DeleteSnapshotSuccess
import akka.persistence.DeleteSnapshotsFailure
import akka.persistence.DeleteSnapshotsSuccess
import akka.persistence.JournalProtocol
import akka.persistence.JournalProtocol._
import akka.persistence.PersistentRepr
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotProtocol
import akka.persistence.journal.Tagged
import akka.persistence.typed.DeleteSnapshotsCompleted
import akka.persistence.typed.DeleteSnapshotsFailed
import akka.persistence.typed.DeleteEventsCompleted
import akka.persistence.typed.DeleteEventsFailed
import akka.persistence.typed.DeletionTarget
import akka.persistence.typed.EventRejectedException
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.internal.Running.WithSeqNrAccessible
import akka.persistence.typed.SnapshotMetadata
import akka.persistence.typed.SnapshotSelectionCriteria
import akka.persistence.typed.scaladsl.Effect
import akka.util.unused

/**
 * INTERNAL API
 *
 * Conceptually fourth (of four) -- also known as 'final' or 'ultimate' -- form of EventSourcedBehavior.
 *
 * In this phase recovery has completed successfully and we continue handling incoming commands,
 * as well as persisting new events as dictated by the user handlers.
 *
 * This behavior operates in three phases (also behaviors):
 * - HandlingCommands - where the command handler is invoked for incoming commands
 * - PersistingEvents - where incoming commands are stashed until persistence completes
 * - storingSnapshot - where incoming commands are stashed until snapshot storage completes
 *
 * This is implemented as such to avoid creating many EventSourced Running instances,
 * which perform the Persistence extension lookup on creation and similar things (config lookup)
 *
 * See previous [[ReplayingEvents]].
 */
@InternalApi
private[akka] object Running {

  trait WithSeqNrAccessible {
    def currentSequenceNumber: Long
  }

  final case class RunningState[State](seqNr: Long, state: State, receivedPoisonPill: Boolean) {

    def nextSequenceNr(): RunningState[State] =
      copy(seqNr = seqNr + 1)

    def updateLastSequenceNr(persistent: PersistentRepr): RunningState[State] =
      if (persistent.sequenceNr > seqNr) copy(seqNr = persistent.sequenceNr) else this

    def applyEvent[C, E](setup: BehaviorSetup[C, E, State], event: E): RunningState[State] = {
      val updated = setup.eventHandler(state, event)
      copy(state = updated)
    }
  }

  def apply[C, E, S](setup: BehaviorSetup[C, E, S], state: RunningState[S]): Behavior[InternalProtocol] = {
    val running = new Running(setup.setMdc(MDC.RunningCmds))
    new running.HandlingCommands(state)
  }
}

// ===============================================

/** INTERNAL API */
@InternalApi private[akka] final class Running[C, E, S](override val setup: BehaviorSetup[C, E, S])
    extends JournalInteractions[C, E, S]
    with SnapshotInteractions[C, E, S]
    with StashManagement[C, E, S] {
  import InternalProtocol._
  import Running.RunningState
  import BehaviorSetup._

  private val runningCmdsMdc = MDC.create(setup.persistenceId, MDC.RunningCmds)
  private val persistingEventsMdc = MDC.create(setup.persistenceId, MDC.PersistingEvents)
  private val storingSnapshotMdc = MDC.create(setup.persistenceId, MDC.StoringSnapshot)

  final class HandlingCommands(state: RunningState[S])
      extends AbstractBehavior[InternalProtocol]
      with WithSeqNrAccessible {

    def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = msg match {
      case IncomingCommand(c: C @unchecked) => onCommand(state, c)
      case JournalResponse(r)               => onDeleteEventsJournalResponse(r, state.state)
      case SnapshotterResponse(r)           => onDeleteSnapshotResponse(r, state.state)
      case _                                => Behaviors.unhandled
    }

    override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
      case PoisonPill =>
        if (isInternalStashEmpty && !isUnstashAllInProgress) Behaviors.stopped
        else new HandlingCommands(state.copy(receivedPoisonPill = true))
      case signal =>
        setup.onSignal(state.state, signal, catchAndLog = false)
        this
    }

    def onCommand(state: RunningState[S], cmd: C): Behavior[InternalProtocol] = {
      val effect = setup.commandHandler(state.state, cmd)
      applyEffects(cmd, state, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
    }

    @tailrec def applyEffects(
        msg: Any,
        state: RunningState[S],
        effect: Effect[E, S],
        sideEffects: immutable.Seq[SideEffect[S]] = Nil): Behavior[InternalProtocol] = {
      if (setup.log.isDebugEnabled && !effect.isInstanceOf[CompositeEffect[_, _]])
        setup.log.debug(
          s"Handled command [{}], resulting effect: [{}], side effects: [{}]",
          msg.getClass.getName,
          effect,
          sideEffects.size)

      effect match {
        case CompositeEffect(eff, currentSideEffects) =>
          // unwrap and accumulate effects
          applyEffects(msg, state, eff, currentSideEffects ++ sideEffects)

        case Persist(event) =>
          // apply the event before persist so that validation exception is handled before persisting
          // the invalid event, in case such validation is implemented in the event handler.
          // also, ensure that there is an event handler for each single event
          val newState = state.applyEvent(setup, event)

          val eventToPersist = adaptEvent(event)

          val newState2 = internalPersist(newState, eventToPersist)

          val shouldSnapshotAfterPersist = setup.shouldSnapshot(newState2.state, event, newState2.seqNr)

          persistingEvents(newState2, state, numberOfEvents = 1, shouldSnapshotAfterPersist, sideEffects)

        case PersistAll(events) =>
          if (events.nonEmpty) {
            // apply the event before persist so that validation exception is handled before persisting
            // the invalid event, in case such validation is implemented in the event handler.
            // also, ensure that there is an event handler for each single event
            var seqNr = state.seqNr
            val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, NoSnapshot: SnapshotAfterPersist)) {
              case ((currentState, snapshot), event) =>
                seqNr += 1
                val shouldSnapshot =
                  if (snapshot == NoSnapshot) setup.shouldSnapshot(currentState.state, event, seqNr) else snapshot
                (currentState.applyEvent(setup, event), shouldSnapshot)
            }

            val eventsToPersist = events.map(adaptEvent)

            val newState2 = internalPersistAll(eventsToPersist, newState)

            persistingEvents(newState2, state, events.size, shouldSnapshotAfterPersist, sideEffects)

          } else {
            // run side-effects even when no events are emitted
            tryUnstashOne(applySideEffects(sideEffects, state))
          }

        case _: PersistNothing.type =>
          tryUnstashOne(applySideEffects(sideEffects, state))

        case _: Unhandled.type =>
          import akka.actor.typed.scaladsl.adapter._
          setup.context.system.toUntyped.eventStream
            .publish(UnhandledMessage(msg, setup.context.system.toUntyped.deadLetters, setup.context.self.toUntyped))
          tryUnstashOne(applySideEffects(sideEffects, state))

        case _: Stash.type =>
          stashUser(IncomingCommand(msg))
          tryUnstashOne(applySideEffects(sideEffects, state))
      }
    }

    def adaptEvent(event: E): Any = {
      val tags = setup.tagger(event)
      val adaptedEvent = setup.eventAdapter.toJournal(event)
      if (tags.isEmpty)
        adaptedEvent
      else
        Tagged(adaptedEvent, tags)
    }

    setup.setMdc(runningCmdsMdc)

    override def currentSequenceNumber: Long = state.seqNr
  }

  // ===============================================

  def persistingEvents(
      state: RunningState[S],
      visibleState: RunningState[S], // previous state until write success
      numberOfEvents: Int,
      shouldSnapshotAfterPersist: SnapshotAfterPersist,
      sideEffects: immutable.Seq[SideEffect[S]]): Behavior[InternalProtocol] = {
    setup.setMdc(persistingEventsMdc)
    new PersistingEvents(state, visibleState, numberOfEvents, shouldSnapshotAfterPersist, sideEffects)
  }

  /** INTERNAL API */
  @InternalApi private[akka] class PersistingEvents(
      var state: RunningState[S],
      var visibleState: RunningState[S], // previous state until write success
      numberOfEvents: Int,
      shouldSnapshotAfterPersist: SnapshotAfterPersist,
      var sideEffects: immutable.Seq[SideEffect[S]],
      persistStartTime: Long = System.nanoTime())
      extends AbstractBehavior[InternalProtocol]
      with WithSeqNrAccessible {

    private var eventCounter = 0

    override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = {
      msg match {
        case JournalResponse(r)                => onJournalResponse(r)
        case in: IncomingCommand[C @unchecked] => onCommand(in)
        case SnapshotterResponse(r)            => onDeleteSnapshotResponse(r, visibleState.state)
        case RecoveryTickEvent(_)              => Behaviors.unhandled
        case RecoveryPermitGranted             => Behaviors.unhandled
      }
    }

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing)
          setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
        Behaviors.unhandled
      } else {
        stashInternal(cmd)
        this
      }
    }

    final def onJournalResponse(response: Response): Behavior[InternalProtocol] = {
      if (setup.log.isDebugEnabled) {
        setup.log.debug("Received Journal response: {} after: {} nanos", response, System.nanoTime() - persistStartTime)
      }

      def onWriteResponse(p: PersistentRepr): Behavior[InternalProtocol] = {
        state = state.updateLastSequenceNr(p)
        eventCounter += 1

        // only once all things are applied we can revert back
        if (eventCounter < numberOfEvents) this
        else {
          visibleState = state
          if (shouldSnapshotAfterPersist == NoSnapshot || state.state == null) {
            tryUnstashOne(applySideEffects(sideEffects, state))
          } else {
            internalSaveSnapshot(state)
            storingSnapshot(state, sideEffects, shouldSnapshotAfterPersist)
          }
        }
      }

      response match {
        case WriteMessageSuccess(p, id) =>
          if (id == setup.writerIdentity.instanceId)
            onWriteResponse(p)
          else this

        case WriteMessageRejected(p, cause, id) =>
          if (id == setup.writerIdentity.instanceId) {
            // current + 1 as it is the inflight event that that has failed
            onWriteRejected(setup.context, cause, p.payload, currentSequenceNumber + 1)
            throw new EventRejectedException(setup.persistenceId, p.sequenceNr, cause)
          } else this

        case WriteMessageFailure(p, cause, id) =>
          if (id == setup.writerIdentity.instanceId) {
            // current + 1 as it is the inflight event that that has failed
            onWriteFailed(setup.context, cause, p.payload, currentSequenceNumber + 1)
            throw new JournalFailureException(setup.persistenceId, p.sequenceNr, p.payload.getClass.getName, cause)
          } else this

        case WriteMessagesSuccessful =>
          // ignore
          this

        case WriteMessagesFailed(_) =>
          // ignore
          this // it will be stopped by the first WriteMessageFailure message; not applying side effects

        case _ =>
          onDeleteEventsJournalResponse(response, visibleState.state)
      }
    }

    override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
      case PoisonPill =>
        // wait for journal responses before stopping
        state = state.copy(receivedPoisonPill = true)
        this
      case signal =>
        setup.onSignal(visibleState.state, signal, catchAndLog = false)
        this
    }

    override def currentSequenceNumber: Long = visibleState.seqNr
  }

  // ===============================================

  def storingSnapshot(
      state: RunningState[S],
      sideEffects: immutable.Seq[SideEffect[S]],
      snapshotReason: SnapshotAfterPersist): Behavior[InternalProtocol] = {
    setup.setMdc(storingSnapshotMdc)

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing)
          setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
        Behaviors.unhandled
      } else {
        stashUser(cmd)
        Behaviors.same
      }
    }

    def onSaveSnapshotResponse(response: SnapshotProtocol.Response): Unit = {
      val signal = response match {
        case SaveSnapshotSuccess(meta) =>
          setup.log.debug(s"Persistent snapshot [{}] saved successfully", meta)
          if (snapshotReason == SnapshotWithRetention) {
            // deletion of old events and snspahots are triggered by the SaveSnapshotSuccess
            setup.retention match {
              case DisabledRetentionCriteria                          => // no further actions
              case s @ SnapshotCountRetentionCriteriaImpl(_, _, true) =>
                // deleteEventsOnSnapshot == true, deletion of old events
                val deleteEventsToSeqNr = s.deleteUpperSequenceNr(meta.sequenceNr)
                internalDeleteEvents(meta.sequenceNr, deleteEventsToSeqNr)
              case s @ SnapshotCountRetentionCriteriaImpl(_, _, false) =>
                // deleteEventsOnSnapshot == false, deletion of old snapshots
                val deleteSnapshotsToSeqNr = s.deleteUpperSequenceNr(meta.sequenceNr)
                internalDeleteSnapshots(s.deleteLowerSequenceNr(deleteSnapshotsToSeqNr), deleteSnapshotsToSeqNr)
            }
          }

          Some(SnapshotCompleted(SnapshotMetadata.fromUntyped(meta)))

        case SaveSnapshotFailure(meta, error) =>
          setup.log.warning("Failed to save snapshot given metadata [{}] due to [{}]", meta, error.getMessage)
          Some(SnapshotFailed(SnapshotMetadata.fromUntyped(meta), error))

        case _ =>
          None
      }

      setup.log.debug("Received snapshot response [{}], emitting signal [{}].", response, signal)
      signal.foreach(setup.onSignal(state.state, _, catchAndLog = false))
    }

    Behaviors
      .receiveMessage[InternalProtocol] {
        case cmd: IncomingCommand[C] @unchecked =>
          onCommand(cmd)
        case JournalResponse(r) =>
          onDeleteEventsJournalResponse(r, state.state)
        case SnapshotterResponse(response) =>
          response match {
            case _: SaveSnapshotSuccess | _: SaveSnapshotFailure =>
              onSaveSnapshotResponse(response)
              tryUnstashOne(applySideEffects(sideEffects, state))
            case _ =>
              onDeleteSnapshotResponse(response, state.state)
          }
        case _ =>
          Behaviors.unhandled
      }
      .receiveSignal {
        case (_, PoisonPill) =>
          // wait for snapshot response before stopping
          storingSnapshot(state.copy(receivedPoisonPill = true), sideEffects, snapshotReason)
        case (_, signal) =>
          setup.onSignal(state.state, signal, catchAndLog = false)
          Behaviors.same
      }

  }

  // --------------------------

  def applySideEffects(effects: immutable.Seq[SideEffect[S]], state: RunningState[S]): Behavior[InternalProtocol] = {
    var behavior: Behavior[InternalProtocol] = new HandlingCommands(state)
    val it = effects.iterator

    // if at least one effect results in a `stop`, we need to stop
    // manual loop implementation to avoid allocations and multiple scans
    while (it.hasNext) {
      val effect = it.next()
      behavior = applySideEffect(effect, state, behavior)
    }

    if (state.receivedPoisonPill && isInternalStashEmpty && !isUnstashAllInProgress)
      Behaviors.stopped
    else
      behavior
  }

  def applySideEffect(
      effect: SideEffect[S],
      state: RunningState[S],
      behavior: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    effect match {
      case _: Stop.type @unchecked =>
        Behaviors.stopped

      case _: UnstashAll.type @unchecked =>
        unstashAll()
        behavior

      case callback: Callback[_] =>
        callback.sideEffect(state.state)
        behavior

      case _ =>
        throw new IllegalArgumentException(s"Unsupported side effect detected [${effect.getClass.getName}]")
    }
  }

  /**
   * Handle journal responses for non-persist events workloads.
   * These are performed in the background and may happen in all phases.
   */
  def onDeleteEventsJournalResponse(response: JournalProtocol.Response, state: S): Behavior[InternalProtocol] = {
    val signal = response match {
      case DeleteMessagesSuccess(toSequenceNr) =>
        setup.log.debug("Persistent events to sequenceNr [{}] deleted successfully.", toSequenceNr)
        setup.retention match {
          case DisabledRetentionCriteria             => // no further actions
          case s: SnapshotCountRetentionCriteriaImpl =>
            // The reason for -1 is that a snapshot at the exact toSequenceNr is still useful and the events
            // after that can be replayed after that snapshot, but replaying the events after toSequenceNr without
            // starting at the snapshot at toSequenceNr would be invalid.
            val deleteSnapshotsToSeqNr = toSequenceNr - 1
            internalDeleteSnapshots(s.deleteLowerSequenceNr(deleteSnapshotsToSeqNr), deleteSnapshotsToSeqNr)
        }
        Some(DeleteEventsCompleted(toSequenceNr))
      case DeleteMessagesFailure(e, toSequenceNr) =>
        Some(DeleteEventsFailed(toSequenceNr, e))
      case _ =>
        None
    }

    signal match {
      case Some(sig) =>
        setup.onSignal(state, sig, catchAndLog = false)
        Behaviors.same
      case None =>
        Behaviors.unhandled // unexpected journal response
    }
  }

  /**
   * Handle snapshot responses for non-persist events workloads.
   * These are performed in the background and may happen in all phases.
   */
  def onDeleteSnapshotResponse(response: SnapshotProtocol.Response, state: S): Behavior[InternalProtocol] = {
    val signal = response match {
      case DeleteSnapshotsSuccess(criteria) =>
        Some(DeleteSnapshotsCompleted(DeletionTarget.Criteria(SnapshotSelectionCriteria.fromUntyped(criteria))))
      case DeleteSnapshotsFailure(criteria, error) =>
        Some(DeleteSnapshotsFailed(DeletionTarget.Criteria(SnapshotSelectionCriteria.fromUntyped(criteria)), error))
      case DeleteSnapshotSuccess(meta) =>
        Some(DeleteSnapshotsCompleted(DeletionTarget.Individual(SnapshotMetadata.fromUntyped(meta))))
      case DeleteSnapshotFailure(meta, error) =>
        Some(DeleteSnapshotsFailed(DeletionTarget.Individual(SnapshotMetadata.fromUntyped(meta)), error))
      case _ =>
        None
    }

    signal match {
      case Some(sig) =>
        setup.onSignal(state, sig, catchAndLog = false)
        Behaviors.same
      case None =>
        Behaviors.unhandled // unexpected snapshot response
    }
  }

  @InternalStableApi
  private[akka] def onWriteFailed(
      @unused ctx: ActorContext[_],
      @unused reason: Throwable,
      @unused event: Any,
      @unused sequenceNr: Long): Unit = ()
  @InternalStableApi
  private[akka] def onWriteRejected(
      @unused ctx: ActorContext[_],
      @unused reason: Throwable,
      @unused event: Any,
      @unused sequenceNr: Long): Unit = ()

}
