/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.annotation.InternalApi
import akka.persistence.Eventsourced.StashingHandlerInvocation
import akka.persistence.JournalProtocol.ReplayMessages
import akka.persistence._
import akka.persistence.SnapshotProtocol.LoadSnapshot
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol

import scala.collection.immutable

@InternalApi
private[akka] trait EventsourcedJournalInteractions[C, E, S] {
  import akka.actor.typed.scaladsl.adapter._

  def setup: EventsourcedSetup[C, E, S]

  private def context = setup.context

  // ---------- journal interactions ---------

  protected def returnRecoveryPermitOnlyOnFailure(cause: Throwable): Unit = {
    setup.log.debug("Returning recovery permit, on failure because: " + cause.getMessage)
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    val permitter = setup.persistence.recoveryPermitter
    permitter.tell(RecoveryPermitter.ReturnRecoveryPermit, setup.selfUntyped)
  }

  type EventOrTagged = Any // `Any` since can be `E` or `Tagged`
  protected def internalPersist(
    state:       EventsourcedRunning.EventsourcedState[S],
    event:       EventOrTagged,
    sideEffects: immutable.Seq[ChainableEffect[_, S]])(handler: Any ⇒ Unit): Behavior[InternalProtocol] = {
    // pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
    val pendingInvocations = StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit]) :: Nil

    val newState = state.nextSequenceNr()

    val senderNotKnownBecauseAkkaTyped = null
    val repr = PersistentRepr(
      event,
      persistenceId = setup.persistenceId,
      sequenceNr = newState.seqNr,
      writerUuid = setup.writerIdentity.writerUuid,
      sender = senderNotKnownBecauseAkkaTyped
    )

    val eventBatch = AtomicWrite(repr) :: Nil // batching not used, since no persistAsync
    setup.journal.tell(JournalProtocol.WriteMessages(eventBatch, setup.selfUntypedAdapted, setup.writerIdentity.instanceId), setup.selfUntypedAdapted)

    EventsourcedRunning.PersistingEvents[C, E, S](setup, state, pendingInvocations, sideEffects)
  }

  protected def internalPersistAll(
    events:      immutable.Seq[EventOrTagged],
    state:       EventsourcedRunning.EventsourcedState[S],
    sideEffects: immutable.Seq[ChainableEffect[_, S]])(handler: Any ⇒ Unit): Behavior[InternalProtocol] = {
    if (events.nonEmpty) {

      val pendingInvocations = events map { event ⇒
        // pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
        StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      }

      val newState = state.nextSequenceNr()

      val senderNotKnownBecauseAkkaTyped = null
      val write = AtomicWrite(events.map(event ⇒ PersistentRepr(
        event,
        persistenceId = setup.persistenceId,
        sequenceNr = newState.seqNr,
        writerUuid = setup.writerIdentity.writerUuid,
        sender = senderNotKnownBecauseAkkaTyped)
      ))

      setup.journal.tell(JournalProtocol.WriteMessages(write :: Nil, setup.selfUntypedAdapted, setup.writerIdentity.instanceId), setup.selfUntypedAdapted)

      EventsourcedRunning.PersistingEvents(setup, state, pendingInvocations, sideEffects)
    } else Behaviors.same
  }

  protected def replayEvents(fromSeqNr: Long, toSeqNr: Long): Unit = {
    setup.log.debug("Replaying messages: from: {}, to: {}", fromSeqNr, toSeqNr)
    // reply is sent to `selfUntypedAdapted`, it is important to target that one
    setup.journal ! ReplayMessages(fromSeqNr, toSeqNr, setup.recovery.replayMax, setup.persistenceId, setup.selfUntypedAdapted)
  }

  protected def returnRecoveryPermit(setup: EventsourcedSetup[_, _, _], reason: String): Unit = {
    setup.log.debug("Returning recovery permit, reason: " + reason)
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    setup.persistence.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, setup.selfUntyped)
  }

  // ---------- snapshot store interactions ---------

  /**
   * Instructs the snapshot store to load the specified snapshot and send it via an [[SnapshotOffer]]
   * to the running [[PersistentActor]].
   */
  protected def loadSnapshot(criteria: SnapshotSelectionCriteria, toSequenceNr: Long): Unit = {
    setup.snapshotStore.tell(LoadSnapshot(setup.persistenceId, criteria, toSequenceNr), setup.selfUntypedAdapted)
  }

  protected def internalSaveSnapshot(state: EventsourcedRunning.EventsourcedState[S]): Unit = {
    setup.snapshotStore.tell(SnapshotProtocol.SaveSnapshot(SnapshotMetadata(setup.persistenceId, state.seqNr), state.state), setup.selfUntypedAdapted)
  }

}
