/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.collection.immutable

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.PreRestart
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi

import akka.persistence.JournalProtocol.ReplayMessages
import akka.persistence.SnapshotProtocol.LoadSnapshot
import akka.persistence._

import akka.util.unused

/** INTERNAL API */
@InternalApi
private[akka] trait JournalInteractions[C, E, S] {

  def setup: BehaviorSetup[C, E, S]

  type EventOrTagged = Any // `Any` since can be `E` or `Tagged`

  protected def internalPersist(
      ctx: ActorContext[_],
      cmd: Any,
      state: Running.RunningState[S],
      event: EventOrTagged,
      eventAdapterManifest: String): Running.RunningState[S] = {

    val newState = state.nextSequenceNr()

    val repr = PersistentRepr(
      event,
      persistenceId = setup.persistenceId.id,
      sequenceNr = newState.seqNr,
      manifest = eventAdapterManifest,
      writerUuid = setup.writerIdentity.writerUuid,
      sender = ActorRef.noSender)

    onWriteInitiated(ctx, cmd, repr)

    val write = AtomicWrite(repr) :: Nil
    setup.journal
      .tell(JournalProtocol.WriteMessages(write, setup.selfClassic, setup.writerIdentity.instanceId), setup.selfClassic)

    newState
  }

  @InternalStableApi
  private[akka] def onWriteInitiated(
      @unused ctx: ActorContext[_],
      @unused cmd: Any,
      @unused repr: PersistentRepr): Unit = ()

  protected def internalPersistAll(
      ctx: ActorContext[_],
      cmd: Any,
      state: Running.RunningState[S],
      events: immutable.Seq[(EventOrTagged, String)]): Running.RunningState[S] = {
    if (events.nonEmpty) {
      var newState = state

      val writes = events.map {
        case (event, eventAdapterManifest) =>
          newState = newState.nextSequenceNr()
          PersistentRepr(
            event,
            persistenceId = setup.persistenceId.id,
            sequenceNr = newState.seqNr,
            manifest = eventAdapterManifest,
            writerUuid = setup.writerIdentity.writerUuid,
            sender = ActorRef.noSender)
      }

      onWritesInitiated(ctx, cmd, writes)
      val write = AtomicWrite(writes)

      setup.journal.tell(
        JournalProtocol.WriteMessages(write :: Nil, setup.selfClassic, setup.writerIdentity.instanceId),
        setup.selfClassic)

      newState
    } else state
  }

  @InternalStableApi
  private[akka] def onWritesInitiated(
      @unused ctx: ActorContext[_],
      @unused cmd: Any,
      @unused repr: immutable.Seq[PersistentRepr]): Unit = ()

  protected def replayEvents(fromSeqNr: Long, toSeqNr: Long): Unit = {
    setup.log.debug2("Replaying messages: from: {}, to: {}", fromSeqNr, toSeqNr)
    setup.journal.tell(
      ReplayMessages(fromSeqNr, toSeqNr, setup.recovery.replayMax, setup.persistenceId.id, setup.selfClassic),
      setup.selfClassic)
  }

  protected def requestRecoveryPermit(): Unit = {
    setup.persistence.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, setup.selfClassic)
  }

  /** Intended to be used in .onSignal(returnPermitOnStop) by behaviors */
  protected def returnPermitOnStop
      : PartialFunction[(ActorContext[InternalProtocol], Signal), Behavior[InternalProtocol]] = {
    case (_, PostStop) =>
      tryReturnRecoveryPermit("PostStop")
      Behaviors.stopped
    case (_, PreRestart) =>
      tryReturnRecoveryPermit("PreRestart")
      Behaviors.stopped
  }

  /** Mutates setup, by setting the `holdingRecoveryPermit` to false */
  protected def tryReturnRecoveryPermit(reason: String): Unit = {
    if (setup.holdingRecoveryPermit) {
      setup.log.debug("Returning recovery permit, reason: {}", reason)
      setup.persistence.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, setup.selfClassic)
      setup.holdingRecoveryPermit = false
    } // else, no need to return the permit
  }

  /**
   * On [[akka.persistence.SaveSnapshotSuccess]], if `SnapshotCountRetentionCriteria.deleteEventsOnSnapshot`
   * is enabled, old messages are deleted based on `SnapshotCountRetentionCriteria.snapshotEveryNEvents`
   * before old snapshots are deleted.
   */
  protected def internalDeleteEvents(lastSequenceNr: Long, toSequenceNr: Long): Unit =
    if (toSequenceNr > 0) {
      val self = setup.selfClassic

      if (toSequenceNr == Long.MaxValue || toSequenceNr <= lastSequenceNr) {
        setup.log.debug("Deleting events up to sequenceNr [{}]", toSequenceNr)
        setup.journal.tell(JournalProtocol.DeleteMessagesTo(setup.persistenceId.id, toSequenceNr, self), self)
      } else
        self ! DeleteMessagesFailure(
          new RuntimeException(
            s"toSequenceNr [$toSequenceNr] must be less than or equal to lastSequenceNr [$lastSequenceNr]"),
          toSequenceNr)
    }
}

/** INTERNAL API */
@InternalApi
private[akka] trait SnapshotInteractions[C, E, S] {

  def setup: BehaviorSetup[C, E, S]

  /**
   * Instructs the snapshot store to load the specified snapshot and send it via an [[SnapshotOffer]]
   * to the running [[PersistentActor]].
   */
  protected def loadSnapshot(criteria: SnapshotSelectionCriteria, toSequenceNr: Long): Unit = {
    setup.snapshotStore.tell(LoadSnapshot(setup.persistenceId.id, criteria, toSequenceNr), setup.selfClassic)
  }

  protected def internalSaveSnapshot(state: Running.RunningState[S]): Unit = {
    setup.log.debug("Saving snapshot sequenceNr [{}]", state.seqNr)
    if (state.state == null)
      throw new IllegalStateException("A snapshot must not be a null state.")
    else
      setup.snapshotStore.tell(
        SnapshotProtocol.SaveSnapshot(
          SnapshotMetadata(setup.persistenceId.id, state.seqNr),
          setup.snapshotAdapter.toJournal(state.state)),
        setup.selfClassic)
  }

  /** Deletes the snapshots up to and including the `sequenceNr`. */
  protected def internalDeleteSnapshots(fromSequenceNr: Long, toSequenceNr: Long): Unit = {
    if (toSequenceNr > 0) {
      val snapshotCriteria = SnapshotSelectionCriteria(minSequenceNr = fromSequenceNr, maxSequenceNr = toSequenceNr)
      setup.log.debug2("Deleting snapshots from sequenceNr [{}] to [{}]", fromSequenceNr, toSequenceNr)
      setup.snapshotStore
        .tell(SnapshotProtocol.DeleteSnapshots(setup.persistenceId.id, snapshotCriteria), setup.selfClassic)
    }
  }
}
