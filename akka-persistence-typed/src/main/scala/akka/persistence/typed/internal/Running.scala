/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable
import akka.actor.UnhandledMessage
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.{ Behavior, Signal }
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors, LoggerOps }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.event.Logging
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
import akka.persistence.query.{ EventEnvelope, PersistenceQuery }
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.persistence.typed.{
  DeleteEventsCompleted,
  DeleteEventsFailed,
  DeleteSnapshotsCompleted,
  DeleteSnapshotsFailed,
  DeletionTarget,
  EventRejectedException,
  PersistenceId,
  SnapshotCompleted,
  SnapshotFailed,
  SnapshotMetadata,
  SnapshotSelectionCriteria
}
import akka.persistence.typed.internal.EventSourcedBehaviorImpl.GetState
import akka.persistence.typed.internal.InternalProtocol.ReplicatedEventEnvelope
import akka.persistence.typed.internal.Running.WithSeqNrAccessible
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior.ActiveActive
import akka.stream.scaladsl.Keep
import akka.stream.{ SharedKillSwitch, SystemMaterializer }
import akka.stream.scaladsl.{ RestartSource, Sink }
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Helpers
import akka.util.OptionVal
import akka.util.unused
import akka.util.Timeout

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

  final case class RunningState[State](
      seqNr: Long,
      state: State,
      receivedPoisonPill: Boolean,
      seenPerReplica: Map[String, Long],
      replicationControl: Map[String, ReplicationStreamControl],
      replicationKillSwitch: Option[SharedKillSwitch] = None) {

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
    val running = new Running(setup.setMdcPhase(PersistenceMdc.RunningCmds))
    val initialState = setup.activeActive match {
      case Some(aa) => startReplicationStream(setup, state, aa)
      case None     => state
    }
    new running.HandlingCommands(initialState)
  }

  def startReplicationStream[C, E, S](
      setup: BehaviorSetup[C, E, S],
      state: RunningState[S],
      aa: ActiveActive): RunningState[S] = {
    import scala.concurrent.duration._
    val system = setup.context.system
    val ref = setup.context.self

    val query = PersistenceQuery(system)
    aa.allReplicas.foldLeft(state) { (state, replicaId) =>
      if (replicaId != aa.replicaId) {
        val seqNr = state.seenPerReplica(replicaId)
        val pid = PersistenceId.replicatedUniqueId(aa.aaContext.entityId, replicaId)
        // FIXME support different configuration per replica https://github.com/akka/akka/issues/29257
        val replication = query.readJournalFor[EventsByPersistenceIdQuery](aa.queryPluginId)

        implicit val timeout = Timeout(30.seconds)

        val controlRef = new AtomicReference[ReplicationStreamControl]()

        val source = RestartSource.withBackoff(2.seconds, 10.seconds, randomFactor = 0.2) { () =>
          replication
            .eventsByPersistenceId(pid.id, seqNr + 1, Long.MaxValue)
            .viaMat(new FastForwardingFilter)(Keep.right)
            .mapMaterializedValue(streamControl => controlRef.set(streamControl))
            .via(ActorFlow.ask[EventEnvelope, ReplicatedEventEnvelope[E], ReplicatedEventAck.type](ref) {
              (eventEnvelope, replyTo) =>
                // Need to handle this not being available migration from non-active-active is supported
                val meta = eventEnvelope.eventMetadata.get.asInstanceOf[ReplicatedEventMetaData]
                val re =
                  ReplicatedEvent[E](eventEnvelope.event.asInstanceOf[E], meta.originReplica, meta.originSequenceNr)
                ReplicatedEventEnvelope(re, replyTo)
            })
        }

        source.runWith(Sink.ignore)(SystemMaterializer(system).materializer)

        // FIXME support from journal to fast forward
        // (how can we support/detect both this and journal ffwd in a backwards compatible way and fallback)
        state.copy(
          replicationControl =
            state.replicationControl.updated(replicaId, new ReplicationStreamControl {
              override def fastForward(sequenceNumber: Long): Unit = {
                // (logging is safe here since invoked on message receive
                OptionVal(controlRef.get) match {
                  case OptionVal.Some(control) =>
                    if (setup.log.isDebugEnabled)
                      setup.log.debug("Fast forward replica [{}] to [{}]", replicaId, sequenceNumber)
                    control.fastForward(sequenceNumber)
                  case OptionVal.None =>
                    // stream not started yet, ok, fast forward is an optimization
                    if (setup.log.isDebugEnabled)
                      setup.log.debug(
                        "Ignoring fast forward replica [{}] to [{}], stream not started yet",
                        replicaId,
                        sequenceNumber)
                }
              }
            }))
      } else {
        state
      }
    }
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
      with WithSeqNrAccessible {

    _currentSequenceNumber = state.seqNr

    private def alreadySeen(e: ReplicatedEvent[_]): Boolean = {
      e.originSequenceNr <= state.seenPerReplica.getOrElse(e.originReplica, 0L)
    }

    def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = msg match {
      case IncomingCommand(c: C @unchecked)          => onCommand(state, c)
      case re: ReplicatedEventEnvelope[E @unchecked] => onReplicatedEvent(state, re, setup.activeActive.get)
      case pe: PublishedEventImpl                    => onPublishedEvent(state, pe)
      case JournalResponse(r)                        => onDeleteEventsJournalResponse(r, state.state)
      case SnapshotterResponse(r)                    => onDeleteSnapshotResponse(r, state.state)
      case get: GetState[S @unchecked]               => onGetState(get)
      case _                                         => Behaviors.unhandled
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
      val effect = setup.commandHandler(state.state, cmd)
      applyEffects(cmd, state, effect.asInstanceOf[EffectImpl[E, S]]) // TODO can we avoid the cast?
    }

    def onReplicatedEvent(
        state: Running.RunningState[S],
        envelope: ReplicatedEventEnvelope[E],
        activeActive: ActiveActive): Behavior[InternalProtocol] = {
      setup.log.infoN(
        "Replica {} received replicated event. Replica seqs nrs: {}. Envelope {}",
        setup.activeActive,
        state.seenPerReplica,
        envelope)
      envelope.ack ! ReplicatedEventAck
      if (envelope.event.originReplica != activeActive.replicaId && !alreadySeen(envelope.event)) {
        activeActive.setContext(false, envelope.event.originReplica)
        setup.log.debug("Saving event as first time")
        handleExternalReplicatedEventPersist(envelope.event)
      } else {
        setup.log.debug("Filtering event as already seen")
        this
      }
    }

    def onPublishedEvent(state: Running.RunningState[S], event: PublishedEventImpl): Behavior[InternalProtocol] = {
      setup.activeActive match {
        case None =>
          setup.log
            .warn("Received published event for [{}] but not an active active actor, dropping", event.persistenceId)
          this

        case Some(activeActive) =>
          event.replicaId match {
            case None =>
              setup.log.warn("Received published event for [{}] but with no replica id, dropping")
              this
            case Some(replicaId) =>
              onPublishedEvent(state, activeActive, replicaId, event)
          }
      }
    }

    private def onPublishedEvent(
        state: Running.RunningState[S],
        activeActive: ActiveActive,
        originReplicaId: String,
        event: PublishedEventImpl): Behavior[InternalProtocol] = {
      val log = setup.log
      val separatorIndex = event.persistenceId.id.indexOf(PersistenceId.DefaultSeparator)
      val idPrefix = event.persistenceId.id.substring(0, separatorIndex)
      if (!setup.persistenceId.id.startsWith(idPrefix)) {
        log.warn("Ignoring published replicated event for the wrong actor [{}]", event.persistenceId)
        this
      } else if (originReplicaId == activeActive.replicaId) {
        // FIXME MultiDC handled this differently not sure why we'd need our own published events?
        if (log.isDebugEnabled)
          log.debug(
            "Ignoring published replicated event with seqNr [{}] from our own replica id [{}]",
            event.sequenceNumber,
            originReplicaId)
        this
      } else if (!activeActive.allReplicas.contains(originReplicaId)) {
        log.warnN(
          "Received published replicated event from replica [{}], which is unknown. Active active must be set up with a list of all replicas (known are [{}]).",
          originReplicaId,
          activeActive.allReplicas.mkString(", "))
        this
      } else {
        val expectedSequenceNumber = state.seenPerReplica(originReplicaId) + 1
        if (expectedSequenceNumber > event.sequenceNumber) {
          // already seen
          if (log.isDebugEnabled)
            log.debugN(
              "Ignoring published replicated event with seqNr [{}] from replica [{}] because it was already seen ([{}])",
              event.sequenceNumber,
              originReplicaId,
              expectedSequenceNumber)
          this
        } else if (expectedSequenceNumber != event.sequenceNumber) {
          //
          if (log.isDebugEnabled) {
            log.debugN(
              "Ignoring published replicated event with replication seqNr [{}] from replica [{}] " +
              "because expected replication seqNr was [{}] ",
              event.sequenceNumber,
              event.replicaId,
              expectedSequenceNumber)
          }
          this
        } else {
          if (log.isTraceEnabled)
            log.traceN(
              "Received published replicated event [{}] with timestamp [{}] from replica [{}] seqNr [{}]",
              Logging.simpleName(event.event.getClass),
              Helpers.timestamp(event.timestamp),
              originReplicaId,
              event.sequenceNumber)

          // fast forward stream for source replica
          state.replicationControl.get(originReplicaId).foreach(_.fastForward(event.sequenceNumber))

          handleExternalReplicatedEventPersist(
            ReplicatedEvent(event.event.asInstanceOf[E], originReplicaId, event.sequenceNumber))
        }

      }
    }

    // Used by EventSourcedBehaviorTestKit to retrieve the state.
    def onGetState(get: GetState[S]): Behavior[InternalProtocol] = {
      get.replyTo ! state.state
      this
    }

    private def handleExternalReplicatedEventPersist(event: ReplicatedEvent[E]): Behavior[InternalProtocol] = {
      _currentSequenceNumber = state.seqNr + 1
      val newState: RunningState[S] = state.applyEvent(setup, event.event)
      val newState2: RunningState[S] = internalPersist(
        setup.context,
        null,
        newState,
        event.event,
        "",
        OptionVal.Some(ReplicatedEventMetaData(event.originReplica, event.originSequenceNr)))
      val shouldSnapshotAfterPersist = setup.shouldSnapshot(newState2.state, event.event, newState2.seqNr)
      // FIXME validate this is the correct sequence nr from that replica https://github.com/akka/akka/issues/29259
      val updatedSeen = newState2.seenPerReplica.updated(event.originReplica, event.originSequenceNr)
      persistingEvents(
        newState2.copy(seenPerReplica = updatedSeen),
        state,
        numberOfEvents = 1,
        shouldSnapshotAfterPersist,
        Nil)
    }

    private def handleEventPersist(event: E, cmd: Any, sideEffects: immutable.Seq[SideEffect[S]]) = {
      // apply the event before persist so that validation exception is handled before persisting
      // the invalid event, in case such validation is implemented in the event handler.
      // also, ensure that there is an event handler for each single event
      _currentSequenceNumber = state.seqNr + 1

      setup.activeActive.foreach { aa =>
        aa.setContext(recoveryRunning = false, aa.replicaId)
      }
      val newState: RunningState[S] = state.applyEvent(setup, event)

      val eventToPersist = adaptEvent(event)
      val eventAdapterManifest = setup.eventAdapter.manifest(event)

      val newState2 = setup.activeActive match {
        case Some(aa) =>
          internalPersist(
            setup.context,
            cmd,
            newState,
            eventToPersist,
            eventAdapterManifest,
            OptionVal.Some(ReplicatedEventMetaData(aa.replicaId, _currentSequenceNumber)))
        case None =>
          internalPersist(setup.context, cmd, newState, eventToPersist, eventAdapterManifest, OptionVal.None)
      }

      val shouldSnapshotAfterPersist = setup.shouldSnapshot(newState2.state, event, newState2.seqNr)
      persistingEvents(newState2, state, numberOfEvents = 1, shouldSnapshotAfterPersist, sideEffects)
    }

    @tailrec def applyEffects(
        msg: Any,
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
          handleEventPersist(event, Some(msg), sideEffects)
        case PersistAll(events) =>
          if (events.nonEmpty) {
            // apply the event before persist so that validation exception is handled before persisting
            // the invalid event, in case such validation is implemented in the event handler.
            // also, ensure that there is an event handler for each single event
            _currentSequenceNumber = state.seqNr
            val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, NoSnapshot: SnapshotAfterPersist)) {
              case ((currentState, snapshot), event) =>
                _currentSequenceNumber += 1
                val shouldSnapshot =
                  if (snapshot == NoSnapshot) setup.shouldSnapshot(currentState.state, event, _currentSequenceNumber)
                  else snapshot
                (currentState.applyEvent(setup, event), shouldSnapshot)
            }

            val eventsToPersist = events.map(evt => (adaptEvent(evt), setup.eventAdapter.manifest(evt)))

            val newState2 = internalPersistAll(setup.context, msg, newState, eventsToPersist)

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

    override def currentSequenceNumber: Long =
      _currentSequenceNumber
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
      with WithSeqNrAccessible {

    private var eventCounter = 0

    override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = {
      msg match {
        case JournalResponse(r)                        => onJournalResponse(r)
        case in: IncomingCommand[C @unchecked]         => onCommand(in)
        case re: ReplicatedEventEnvelope[E @unchecked] => onReplicatedEvent(re)
        case pe: PublishedEventImpl                    => onPublishedEvent(pe)
        case get: GetState[S @unchecked]               => stashInternal(get)
        case SnapshotterResponse(r)                    => onDeleteSnapshotResponse(r, visibleState.state)
        case RecoveryTickEvent(_)                      => Behaviors.unhandled
        case RecoveryPermitGranted                     => Behaviors.unhandled
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

    def onReplicatedEvent(event: InternalProtocol.ReplicatedEventEnvelope[E]): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        Behaviors.unhandled
      } else {
        stashInternal(event)
      }
    }

    def onPublishedEvent(event: PublishedEventImpl): Behavior[InternalProtocol] = {
      if (state.receivedPoisonPill) {
        Behaviors.unhandled
      } else {
        stashInternal(event)
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
        state = state.updateLastSequenceNr(p)
        eventCounter += 1

        onWriteSuccess(setup.context, p)

        if (setup.publishEvents) {
          context.system.eventStream ! EventStream.Publish(
            PublishedEventImpl(setup.replicaId, setup.persistenceId, p.sequenceNr, p.payload, p.timestamp))
        }

        // only once all things are applied we can revert back
        if (eventCounter < numberOfEvents) {
          onWriteDone(setup.context, p)
          this
        } else {
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

    override def currentSequenceNumber: Long = {
      _currentSequenceNumber
    }
  }

  // ===============================================

  /** INTERNAL API */
  @InternalApi private[akka] class StoringSnapshot(
      state: RunningState[S],
      sideEffects: immutable.Seq[SideEffect[S]],
      snapshotReason: SnapshotAfterPersist)
      extends AbstractBehavior[InternalProtocol](setup.context)
      with WithSeqNrAccessible {
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

    override def currentSequenceNumber: Long =
      _currentSequenceNumber
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
