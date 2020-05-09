/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.annotation.tailrec
import scala.collection.{ immutable, mutable }
import akka.actor.UnhandledMessage
import akka.actor.typed.{ Behavior, Signal }
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors, LoggerOps }
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
import akka.persistence.typed.DeleteEventsCompleted
import akka.persistence.typed.DeleteEventsFailed
import akka.persistence.typed.DeleteSnapshotsCompleted
import akka.persistence.typed.DeleteSnapshotsFailed
import akka.persistence.typed.DeletionTarget
import akka.persistence.typed.EventRejectedException
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.persistence.typed.SnapshotMetadata
import akka.persistence.typed.SnapshotSelectionCriteria
import akka.persistence.typed.IdempotencyKeyWriteRejectedException
import akka.persistence.typed.WriteIdempotencyKeyFailed
import akka.persistence.typed.WriteIdempotencyKeySucceeded
import akka.persistence.typed.internal.EventSourcedBehaviorImpl.GetState
import akka.persistence.typed.internal.Running.WithIdempotencyKeyCacheAccessible
import akka.persistence.typed.internal.Running.WithSeqNrAccessible
import akka.persistence.typed.scaladsl.{ Effect, IdempotenceFailure, IdempotentCommand }
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
    def currentEventSequenceNumber: Long
    def currentIdempotencyKeySequenceNumber: Long
  }

  trait WithIdempotencyKeyCacheAccessible {
    def idempotencyKeyCacheContent: immutable.Seq[String]
  }

  trait IdempotencyKeyCache {

    def content: immutable.Seq[String]

    val maxSize: Long

    def contains(key: String): (Boolean, IdempotencyKeyCache)

    def addKey(key: String): IdempotencyKeyCache
  }

  case object NoCache extends IdempotencyKeyCache {

    override val content: immutable.Seq[String] = immutable.Seq.empty

    override val maxSize: Long = 0

    override def contains(key: String): (Boolean, IdempotencyKeyCache) = (false, this)

    override def addKey(key: String): IdempotencyKeyCache = this
  }

  case class LRUCache(private val keySet: mutable.LinkedHashSet[String], maxSize: Long) extends IdempotencyKeyCache {

    override def contains(key: String): (Boolean, IdempotencyKeyCache) = {
      val contains = keySet.contains(key)
      if (contains) {
        keySet.remove(key)
        keySet.add(key)
      }
      (contains, this)
    }

    override def addKey(key: String): IdempotencyKeyCache = {
      val contains = keySet.contains(key)
      if (contains) {
        keySet.remove(key)
        keySet.add(key)
      } else {
        if (keySet.size == maxSize) {
          keySet.remove(keySet.head)
        }
        keySet.add(key)
      }
      this
    }

    override def content: immutable.Seq[String] = {
      immutable.Seq.concat(keySet)
    }
  }

  final case class RunningState[State](
      eventSeqNr: Long,
      state: State,
      receivedPoisonPill: Boolean,
      idempotenceKeySeqNr: Long,
      idempotenceKeyCache: IdempotencyKeyCache) {

    def nextEventSequenceNr(): RunningState[State] =
      copy(eventSeqNr = eventSeqNr + 1)

    def updateLastEventSequenceNr(persistent: PersistentRepr): RunningState[State] =
      if (persistent.sequenceNr > eventSeqNr) copy(eventSeqNr = persistent.sequenceNr) else this

    def nextIdempotenceKeySequenceNr(): RunningState[State] =
      copy(idempotenceKeySeqNr = idempotenceKeySeqNr + 1)

    def updateLastIdempotenceKeySequenceNr(seqNr: Long): RunningState[State] =
      copy(idempotenceKeySeqNr = seqNr)

    def applyEvent[C, E](setup: BehaviorSetup[C, E, State], event: E): RunningState[State] = {
      val updated = setup.eventHandler(state, event)
      copy(state = updated)
    }

    def checkIdempotenceKeyCache(idempotenceKey: String): (Boolean, RunningState[State]) = {
      val (contains, cache) = idempotenceKeyCache.contains(idempotenceKey)
      (contains, copy(idempotenceKeyCache = cache))
    }

    def addIdempotenceKeyToCache(idempotenceKey: String): RunningState[State] = {
      copy(idempotenceKeyCache = idempotenceKeyCache.addKey(idempotenceKey))
    }
  }

  def apply[C, E, S](setup: BehaviorSetup[C, E, S], state: RunningState[S]): Behavior[InternalProtocol] = {
    val running = new Running(setup.setMdcPhase(PersistenceMdc.RunningCmds))
    new running.HandlingCommands(state)
  }
}

// ===============================================

/** INTERNAL API */
@InternalApi private[akka] final class Running[C, E, S](override val setup: BehaviorSetup[C, E, S])
    extends JournalInteractions[C, E, S]
    with SnapshotInteractions[C, E, S]
    with StashManagement[C, E, S] {
  import BehaviorSetup._
  import InternalProtocol._
  import Running.RunningState

  // Needed for WithSeqNrAccessible, when unstashing
  private var _currentSequenceNumber = 0L

  final class HandlingCommands(state: RunningState[S])
      extends AbstractBehavior[InternalProtocol](setup.context)
      with WithSeqNrAccessible
      with WithIdempotencyKeyCacheAccessible {

    _currentSequenceNumber = state.seqNr

    def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = msg match {
      case IncomingCommand(c: C @unchecked) => onCommand(state, c)
      case JournalResponse(r)               => onDeleteEventsJournalResponse(r, state.state)
      case SnapshotterResponse(r)           => onDeleteSnapshotResponse(r, state.state)
      case get: GetState[S @unchecked]      => onGetState(get)
      case _                                => Behaviors.unhandled
    }

    override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
      case PoisonPill =>
        if (isInternalStashEmpty && !isUnstashAllInProgress) Behaviors.stopped
        else new HandlingCommands(state.copy(receivedPoisonPill = true))
      case signal =>
        if (setup.onSignal(state.state, signal, catchAndLog = false)) this
        else Behaviors.unhandled
    }

    def onCommand(state: RunningState[S], cmd: C): Behavior[InternalProtocol] = {
      cmd match {
        case ic: IdempotentCommand[_, S] =>
          val (contains, newState) = state.checkIdempotenceKeyCache(ic.idempotencyKey)
          if (contains) {
            ic.replyTo ! IdempotenceFailure(newState.state)
            new HandlingCommands(newState)
          } else {
            internalCheckIdempotencyKeyExists(ic.idempotencyKey)
            new CheckingIdempotenceKey(newState, ic.asInstanceOf[C with IdempotentCommand[Any, S]], ic.idempotencyKey) // TODO can we avoid the cast?
          }
        case _ =>
          val effect = setup.commandHandler(state.state, cmd)
          applyEffects(cmd, state, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
      }
    }

    // Used by EventSourcedBehaviorTestKit to retrieve the state.
    def onGetState(get: GetState[S]): Behavior[InternalProtocol] = {
      get.replyTo ! state.state
      this
    }

    @tailrec def applyEffects(
        msg: C,
        state: RunningState[S],
        effect: Effect[E, S],
        sideEffects: immutable.Seq[SideEffect[S]] = Nil): Behavior[InternalProtocol] = {
      if (setup.log.isDebugEnabled && !effect.isInstanceOf[CompositeEffect[_, _]])
        setup.log.debugN(
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
          _currentSequenceNumber = state.seqNr + 1
          val newState = state.applyEvent(setup, event)

          val eventToPersist = adaptEvent(event)
          val eventAdapterManifest = setup.eventAdapter.manifest(event)

          val idempotencyKey = msg match {
            case ic: IdempotentCommand[_, _] =>
              Some(ic.idempotencyKey)
            case _ =>
              None
          }

          val newState2 =
            internalPersist(setup.context, msg, newState, eventToPersist, eventAdapterManifest, idempotencyKey)

          val shouldSnapshotAfterPersist = setup.shouldSnapshot(newState2.state, event, newState2.eventSeqNr)

          persistingEvents(newState2, state, numberOfEvents = 1, shouldSnapshotAfterPersist, sideEffects)

        case PersistAll(events) =>
          if (events.nonEmpty) {
            // apply the event before persist so that validation exception is handled before persisting
            // the invalid event, in case such validation is implemented in the event handler.
            // also, ensure that there is an event handler for each single event
            _currentSequenceNumber = state.eventSeqNr
            val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, NoSnapshot: SnapshotAfterPersist)) {
              case ((currentState, snapshot), event) =>
                _currentSequenceNumber += 1
                val shouldSnapshot =
                  if (snapshot == NoSnapshot) setup.shouldSnapshot(currentState.state, event, _currentSequenceNumber)
                  else snapshot
                (currentState.applyEvent(setup, event), shouldSnapshot)
            }

            val eventsToPersist = events.map(evt => (adaptEvent(evt), setup.eventAdapter.manifest(evt)))

            val idempotencyKey = msg match {
              case ic: IdempotentCommand[_, _] =>
                Some(ic.idempotencyKey)
              case _ =>
                None
            }

            val newState2 = internalPersistAll(setup.context, msg, newState, eventsToPersist, idempotencyKey)

            persistingEvents(newState2, state, events.size, shouldSnapshotAfterPersist, sideEffects)

          } else {
            // run side-effects even when no events are emitted
            tryUnstashOne(applySideEffects(sideEffects, state))
          }

        case _: PersistNothing.type =>
          tryUnstashOne(applySideEffects(sideEffects, state))

        case _: Unhandled.type =>
          import akka.actor.typed.scaladsl.adapter._
          setup.context.system.toClassic.eventStream
            .publish(UnhandledMessage(msg, setup.context.system.toClassic.deadLetters, setup.context.self.toClassic))
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

    setup.setMdcPhase(PersistenceMdc.RunningCmds)

    override def currentEventSequenceNumber: Long = _currentSequenceNumber
    override def currentIdempotencyKeySequenceNumber: Long = state.idempotenceKeySeqNr
    override def idempotencyKeyCacheContent: immutable.Seq[String] = state.idempotenceKeyCache.content
  }

  // ===============================================

  def persistingEvents(
      state: RunningState[S],
      visibleState: RunningState[S], // previous state until write success
      numberOfEvents: Int,
      shouldSnapshotAfterPersist: SnapshotAfterPersist,
      sideEffects: immutable.Seq[SideEffect[S]]): Behavior[InternalProtocol] = {
    setup.setMdcPhase(PersistenceMdc.PersistingEvents)
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
      extends AbstractBehavior[InternalProtocol](setup.context)
      with WithSeqNrAccessible
      with WithIdempotencyKeyCacheAccessible {

    private var eventCounter = 0
    private var writtenIdempotencePayload = Option.empty[(String, Long)]

    override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = {
      msg match {
        case JournalResponse(r)                => onJournalResponse(r)
        case in: IncomingCommand[C @unchecked] => onCommand(in)
        case get: GetState[S @unchecked]       => stashInternal(get)
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
      }
    }

    final def onJournalResponse(response: Response): Behavior[InternalProtocol] = {
      if (setup.log.isDebugEnabled) {
        setup.log.debug2(
          "Received Journal response: {} after: {} nanos",
          response,
          System.nanoTime() - persistStartTime)
      }

      def onWriteResponse(p: PersistentRepr): Behavior[InternalProtocol] = {
        state = state.updateLastEventSequenceNr(p)
        eventCounter += 1

        onWriteSuccess(setup.context, p)

        // only once all things are applied we can revert back
        if (eventCounter < numberOfEvents) {
          onWriteDone(setup.context, p)
          this
        } else {
          writtenIdempotencePayload.foreach {
            case (key, seqNr) =>
              state = state.addIdempotenceKeyToCache(key).updateLastIdempotenceKeySequenceNr(seqNr)
          }
          visibleState = state
          if (shouldSnapshotAfterPersist == NoSnapshot || state.state == null) {
            val newState = applySideEffects(sideEffects, state)

            onWriteDone(setup.context, p)

            tryUnstashOne(newState)
          } else {
            internalSaveSnapshot(state)
            new StoringSnapshot(state, sideEffects, shouldSnapshotAfterPersist)
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
            onWriteRejected(setup.context, cause, p)
            throw new EventRejectedException(setup.persistenceId, p.sequenceNr, cause)
          } else this

        case WriteMessageFailure(p, cause, id) =>
          if (id == setup.writerIdentity.instanceId) {
            onWriteFailed(setup.context, cause, p)
            throw new JournalFailureException(setup.persistenceId, p.sequenceNr, p.payload.getClass.getName, cause)
          } else this

        case WriteMessagesSuccessful =>
          // ignore
          this

        case WriteMessagesFailed(_, _) =>
          // ignore
          this // it will be stopped by the first WriteMessageFailure message; not applying side effects

        case WriteIdempotencyKeySuccess(key, seqNr, id) =>
          if (id == setup.writerIdentity.instanceId) {
            setup.onSignal(state.state, WriteIdempotencyKeySucceeded(key, seqNr), catchAndLog = false)
            // apply to state once messages are written successfully
            writtenIdempotencePayload = Some(key, seqNr)
          }
          this

        case WriteIdempotencyKeyRejected(_, _, _, _) =>
          // handled with messages rejection
          this

        case WriteIdempotencyKeyFailure(_, _, _, _) =>
          // handled with messages failure
          this

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
        if (setup.onSignal(visibleState.state, signal, catchAndLog = false)) this
        else Behaviors.unhandled
    }

    override def currentEventSequenceNumber: Long = _currentSequenceNumber
    override def currentIdempotencyKeySequenceNumber: Long = visibleState.idempotenceKeySeqNr
    override def idempotencyKeyCacheContent: immutable.Seq[String] = visibleState.idempotenceKeyCache.content
  }

  // ===============================================

  /** INTERNAL API */
  @InternalApi private[akka] class StoringSnapshot(
      state: RunningState[S],
      sideEffects: immutable.Seq[SideEffect[S]],
      snapshotReason: SnapshotAfterPersist)
      extends AbstractBehavior[InternalProtocol](setup.context)
      with WithSeqNrAccessible
      with WithIdempotencyKeyCacheAccessible {
    setup.setMdcPhase(PersistenceMdc.StoringSnapshot)

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing)
          setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
        Behaviors.unhandled
      } else {
        stashInternal(cmd)
      }
    }

    def onSaveSnapshotResponse(response: SnapshotProtocol.Response): Unit = {
      val signal = response match {
        case SaveSnapshotSuccess(meta) =>
          setup.log.debug(s"Persistent snapshot [{}] saved successfully", meta)
          if (snapshotReason == SnapshotWithRetention) {
            // deletion of old events and snapshots are triggered by the SaveSnapshotSuccess
            setup.retention match {
              case DisabledRetentionCriteria                          => // no further actions
              case s @ SnapshotCountRetentionCriteriaImpl(_, _, true) =>
                // deleteEventsOnSnapshot == true, deletion of old events
                val deleteEventsToSeqNr = s.deleteUpperSequenceNr(meta.sequenceNr)
                // snapshot deletion then happens on event deletion success in Running.onDeleteEventsJournalResponse
                internalDeleteEvents(meta.sequenceNr, deleteEventsToSeqNr)
              case s @ SnapshotCountRetentionCriteriaImpl(_, _, false) =>
                // deleteEventsOnSnapshot == false, deletion of old snapshots
                val deleteSnapshotsToSeqNr = s.deleteUpperSequenceNr(meta.sequenceNr)
                internalDeleteSnapshots(s.deleteLowerSequenceNr(deleteSnapshotsToSeqNr), deleteSnapshotsToSeqNr)
            }
          }

          Some(SnapshotCompleted(SnapshotMetadata.fromClassic(meta)))

        case SaveSnapshotFailure(meta, error) =>
          setup.log.warn2("Failed to save snapshot given metadata [{}] due to: {}", meta, error.getMessage)
          Some(SnapshotFailed(SnapshotMetadata.fromClassic(meta), error))

        case _ =>
          None
      }

      signal match {
        case Some(signal) =>
          setup.log.debug("Received snapshot response [{}].", response)
          if (setup.onSignal(state.state, signal, catchAndLog = false)) {
            setup.log.debug("Emitted signal [{}].", signal)
          }
        case None =>
          setup.log.debug("Received snapshot response [{}], no signal emitted.", response)
      }
    }

    def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = msg match {
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
      case get: GetState[S @unchecked] =>
        stashInternal(get)
      case _ =>
        Behaviors.unhandled
    }

    override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
      case PoisonPill =>
        // wait for snapshot response before stopping
        new StoringSnapshot(state.copy(receivedPoisonPill = true), sideEffects, snapshotReason)
      case signal =>
        if (setup.onSignal(state.state, signal, catchAndLog = false))
          Behaviors.same
        else
          Behaviors.unhandled
    }

    override def currentEventSequenceNumber: Long = _currentSequenceNumber
    override def currentIdempotencyKeySequenceNumber: Long = state.idempotenceKeySeqNr
    override def idempotencyKeyCacheContent: immutable.Seq[String] = state.idempotenceKeyCache.content
  }

  // --------------------------

  @InternalApi private[akka] class CheckingIdempotenceKey[IC <: C with IdempotentCommand[_, S]](
      state: RunningState[S],
      pendingCommand: IC,
      idempotencyKey: String)
      extends AbstractBehavior[InternalProtocol](setup.context) {

    override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = msg match {
      case cmd: IncomingCommand[C] @unchecked =>
        onCommand(cmd)
      case JournalResponse(r)     => onJournalResponse(r)
      case SnapshotterResponse(r) => onDeleteSnapshotResponse(r, state.state)
      case _ =>
        Behaviors.unhandled
    }

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing)
          setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
        Behaviors.unhandled
      } else {
        stashInternal(cmd)
        Behaviors.same
      }
    }

    final def onJournalResponse(response: Response): Behavior[InternalProtocol] = {
      response match {
        case IdempotencyCheckSuccess(false) =>
          setup.onSignal(
            state.state,
            CheckIdempotencyKeyExistsSucceeded(idempotencyKey, exists = false),
            catchAndLog = false)

          val effect = setup.commandHandler(state.state, pendingCommand).asInstanceOf[EffectImpl[E, S]] // TODO can we avoid the cast?

          val persistEffectPresent = {
            @scala.annotation.tailrec
            def persistEffectPresent(effect: EffectImpl[E, S]): Boolean = effect match {
              case Persist(_) =>
                true
              case PersistAll(_) =>
                true
              case CompositeEffect(eff, _) =>
                persistEffectPresent(eff.asInstanceOf[EffectImpl[E, S]])
              case _ =>
                false
            }
            persistEffectPresent(effect)
          }

          if (pendingCommand.writeConfig.doExplicitWrite(persistEffectPresent)) {
            val newState = internalWriteIdempotencyKey(state, idempotencyKey)
            new WritingIdempotenceKey(newState, pendingCommand)
          } else {
            val running = new HandlingCommands(state)
            running.applyEffects(pendingCommand, state, effect)
          }
        case IdempotencyCheckSuccess(true) =>
          setup.onSignal(
            state.state,
            CheckIdempotencyKeyExistsSucceeded(idempotencyKey, exists = true),
            catchAndLog = false)

          pendingCommand.replyTo ! IdempotenceFailure(state.state)
          val newState = state.addIdempotenceKeyToCache(idempotencyKey)
          tryUnstashOne(new HandlingCommands(newState))
        case IdempotencyCheckFailure(cause) =>
          setup.onSignal(state.state, CheckIdempotencyKeyExistsFailed(idempotencyKey, cause), catchAndLog = false)

          val msg = "Exception while checking for idempotency key existence. " +
            s"PersistenceId [${setup.persistenceId.id}]. ${cause.getMessage}"
          throw new JournalFailureException(msg, cause)
        case _ =>
          onDeleteEventsJournalResponse(response, state.state)
      }
    }
  }

  // --------------------------

  @InternalApi private[akka] class WritingIdempotenceKey[IC <: C with IdempotentCommand[_, S]](
      state: RunningState[S],
      pendingCommand: IC)
      extends AbstractBehavior[InternalProtocol](setup.context) {

    override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = msg match {
      case cmd: IncomingCommand[C] @unchecked =>
        onCommand(cmd)
      case JournalResponse(r)     => onJournalResponse(r)
      case SnapshotterResponse(r) => onDeleteSnapshotResponse(r, state.state)
      case _ =>
        Behaviors.unhandled
    }

    def onCommand(cmd: IncomingCommand[C]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        if (setup.settings.logOnStashing)
          setup.log.debug("Discarding message [{}], because actor is to be stopped.", cmd)
        Behaviors.unhandled
      } else {
        stashInternal(cmd)
        Behaviors.same
      }
    }

    final def onJournalResponse(response: Response): Behavior[InternalProtocol] = {
      response match {
        case WriteIdempotencyKeySuccess(idempotencyKey, sequenceNr, _) =>
          setup.onSignal(state.state, WriteIdempotencyKeySucceeded(idempotencyKey, sequenceNr), catchAndLog = false)

          val newState = state.addIdempotenceKeyToCache(idempotencyKey).updateLastIdempotenceKeySequenceNr(sequenceNr)
          val effect = setup.commandHandler(newState.state, pendingCommand)
          val running = new HandlingCommands(newState)
          running.applyEffects(pendingCommand, newState, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
        case WriteIdempotencyKeyFailure(key, seqNr, cause, _) =>
          setup.onSignal(state.state, WriteIdempotencyKeyFailed(key, seqNr, cause), catchAndLog = false)

          val msg = "Exception while writing idempotency key. " +
            s"PersistenceId [${setup.persistenceId.id}]. ${cause.getMessage}"
          throw new JournalFailureException(msg, cause)
        case WriteIdempotencyKeyRejected(key, seqNr, cause, _) =>
          throw new IdempotencyKeyWriteRejectedException(setup.persistenceId, key, seqNr, cause)
        case _ =>
          onDeleteEventsJournalResponse(response, state.state)
      }
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
        if (setup.onSignal(state, sig, catchAndLog = false)) Behaviors.same else Behaviors.unhandled
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
        Some(DeleteSnapshotsCompleted(DeletionTarget.Criteria(SnapshotSelectionCriteria.fromClassic(criteria))))
      case DeleteSnapshotsFailure(criteria, error) =>
        Some(DeleteSnapshotsFailed(DeletionTarget.Criteria(SnapshotSelectionCriteria.fromClassic(criteria)), error))
      case DeleteSnapshotSuccess(meta) =>
        Some(DeleteSnapshotsCompleted(DeletionTarget.Individual(SnapshotMetadata.fromClassic(meta))))
      case DeleteSnapshotFailure(meta, error) =>
        Some(DeleteSnapshotsFailed(DeletionTarget.Individual(SnapshotMetadata.fromClassic(meta)), error))
      case _ =>
        None
    }

    signal match {
      case Some(sig) =>
        if (setup.onSignal(state, sig, catchAndLog = false)) Behaviors.same else Behaviors.unhandled
      case None =>
        Behaviors.unhandled // unexpected snapshot response
    }
  }

  @InternalStableApi
  private[akka] def onWriteFailed(
      @unused ctx: ActorContext[_],
      @unused reason: Throwable,
      @unused event: PersistentRepr): Unit = ()
  @InternalStableApi
  private[akka] def onWriteRejected(
      @unused ctx: ActorContext[_],
      @unused reason: Throwable,
      @unused event: PersistentRepr): Unit = ()
  @InternalStableApi
  private[akka] def onWriteSuccess(@unused ctx: ActorContext[_], @unused event: PersistentRepr): Unit = ()
  @InternalStableApi
  private[akka] def onWriteDone(@unused ctx: ActorContext[_], @unused event: PersistentRepr): Unit = ()
}
