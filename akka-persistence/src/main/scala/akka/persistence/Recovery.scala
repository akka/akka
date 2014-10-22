/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.dispatch.Envelope
import akka.persistence.JournalProtocol._
import akka.persistence.SnapshotProtocol.LoadSnapshotResult

import scala.util.control.NonFatal

/**
 * Recovery state machine that loads snapshots and replays messages.
 *
 * @see [[PersistentActor]]
 * @see [[PersistentView]]
 */
trait Recovery extends Actor with Snapshotter with Stash with StashFactory {
  /**
   * INTERNAL API.
   *
   * Recovery state.
   */
  private[persistence] trait State {
    def aroundReceive(receive: Receive, message: Any): Unit

    protected def process(receive: Receive, message: Any) =
      // calls `Recovery.super.aroundReceive` to allow Processor to be used as a stackable modification
      Recovery.super.aroundReceive(receive, message)

    protected def processPersistent(receive: Receive, persistent: Persistent) =
      withCurrentPersistent(persistent)(runReceive(receive))

    protected def recordFailure(cause: Throwable): Unit = {
      _recoveryFailureCause = cause
      _recoveryFailureMessage = context.asInstanceOf[ActorCell].currentMessage
    }
  }

  /**
   * INTERNAL API.
   *
   * This is used to deliver a persistent message to the actor’s behavior
   * through withCurrentPersistent().
   */
  private[persistence] def runReceive(receive: Receive)(msg: Persistent): Unit =
    // calls `Recovery.super.aroundReceive` to allow Processor to be used as a stackable modification
    Recovery.super.aroundReceive(receive, msg)

  /**
   * INTERNAL API.
   *
   * Initial state, waits for `Recover` request, submit a `LoadSnapshot` request to the snapshot
   * store and changes to `recoveryStarted` state.
   */
  private[persistence] val recoveryPending = new State {
    override def toString: String = "recovery pending"

    def aroundReceive(receive: Receive, message: Any): Unit = message match {
      case Recover(fromSnap, toSnr, replayMax) ⇒
        _currentState = recoveryStarted(replayMax)
        loadSnapshot(snapshotterId, fromSnap, toSnr)
      case _ ⇒ receiverStash.stash()
    }
  }

  /**
   * INTERNAL API.
   *
   * Processes a loaded snapshot, if any. A loaded snapshot is offered with a `SnapshotOffer`
   * message to the actor's current behavior. Then initiates a message replay, either starting
   * from the loaded snapshot or from scratch, and switches to `replayStarted` state.
   *
   * @param replayMax maximum number of messages to replay.
   */
  private[persistence] def recoveryStarted(replayMax: Long) = new State {
    override def toString: String = s"recovery started (replayMax = [${replayMax}])"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case r: Recover ⇒ // ignore
      case LoadSnapshotResult(sso, toSnr) ⇒
        sso.foreach {
          case SelectedSnapshot(metadata, snapshot) ⇒
            updateLastSequenceNr(metadata.sequenceNr)
            process(receive, SnapshotOffer(metadata, snapshot))
        }
        _currentState = replayStarted(await = true)
        journal ! ReplayMessages(lastSequenceNr + 1L, toSnr, replayMax, persistenceId, self)
      case other ⇒ receiverStash.stash()
    }
  }

  /**
   * INTERNAL API.
   *
   * Processes replayed messages, if any. The actor's current behavior is invoked with the replayed
   * [[Persistent]] messages. If processing of a replayed message fails, the exception is caught and
   * stored for being thrown later and state is changed to `recoveryFailed`. If replay succeeds the
   * `onReplaySuccess` method is called, otherwise `onReplayFailure`.
   *
   * @param await if `true` processing of further messages will be delayed until replay completes,
   *              otherwise, the actor's behavior is invoked immediately with these messages.
   */
  private[persistence] def replayStarted(await: Boolean) = new State {
    override def toString: String = s"replay started (await = [${await}])"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case r: Recover ⇒ // ignore
      case ReplayedMessage(p) ⇒
        try processPersistent(receive, p) catch {
          case NonFatal(t) ⇒
            _currentState = replayFailed // delay throwing exception to prepareRestart
            recordFailure(t)
        }
      case ReplayMessagesSuccess        ⇒ onReplaySuccess(receive, await)
      case ReplayMessagesFailure(cause) ⇒ onReplayFailure(receive, await, cause)
      case other ⇒
        if (await) receiverStash.stash() else process(receive, other)
    }
  }

  /**
   * INTERNAL API.
   *
   * Consumes remaining replayed messages and then changes to `prepareRestart`. The
   * message that caused the exception during replay, is re-added to the mailbox and
   * re-received in `prepareRestart`.
   */
  private[persistence] val replayFailed = new State {
    override def toString: String = "replay failed"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case ReplayMessagesFailure(_) ⇒
        replayCompleted()
        // journal couldn't tell the maximum stored sequence number, hence the next
        // replay must be a full replay (up to the highest stored sequence number)
        updateLastSequenceNr(Long.MaxValue)
      case ReplayMessagesSuccess ⇒ replayCompleted()
      case ReplayedMessage(p)    ⇒ updateLastSequenceNr(p)
      case r: Recover            ⇒ // ignore
      case _                     ⇒ receiverStash.stash()
    }

    def replayCompleted(): Unit = {
      _currentState = prepareRestart
      mailbox.enqueueFirst(self, _recoveryFailureMessage)
    }
  }

  /**
   * INTERNAL API.
   *
   * Re-receives the replayed message that caused an exception and re-throws that exception.
   */
  private[persistence] val prepareRestart = new State {
    override def toString: String = "prepare restart"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case ReplayedMessage(_) ⇒ throw _recoveryFailureCause
      case _                  ⇒ // ignore
    }
  }

  private var _recoveryFailureCause: Throwable = _
  private var _recoveryFailureMessage: Envelope = _

  private var _lastSequenceNr: Long = 0L
  private var _currentPersistent: Persistent = _

  /**
   * Id of the processor for which messages should be replayed.
   */
  @deprecated("Override `persistenceId` instead. Processor will be removed.", since = "2.3.4")
  def processorId: String = extension.persistenceId(self) // TODO: remove processorId

  /**
   * Id of the persistent entity for which messages should be replayed.
   */
  def persistenceId: String

  /** INTERNAL API */
  private[persistence] def withCurrentPersistent(persistent: Persistent)(body: Persistent ⇒ Unit): Unit = try {
    _currentPersistent = persistent
    updateLastSequenceNr(persistent)
    body(persistent)
  } finally _currentPersistent = null

  /** INTERNAL API. */
  private[persistence] def updateLastSequenceNr(persistent: Persistent): Unit =
    if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr

  /** INTERNAL API. */
  private[persistence] def updateLastSequenceNr(value: Long): Unit =
    _lastSequenceNr = value

  /**
   * Returns the current persistent message if there is any.
   */
  @deprecated("currentPersistentMessage will be removed, sequence number can be retrieved with `lastSequenceNr`.", since = "2.3.4")
  implicit def currentPersistentMessage: Option[Persistent] = Option(_currentPersistent)

  /**
   * Java API: returns the current persistent message or `null` if there is none.
   */
  @deprecated("getCurrentPersistentMessage will be removed, sequence number can be retrieved with `lastSequenceNr`.", since = "2.3.4")
  def getCurrentPersistentMessage = currentPersistentMessage.getOrElse(null)

  /**
   * Highest received sequence number so far or `0L` if this actor hasn't received a persistent
   * message yet. Usually equal to the sequence number of `currentPersistentMessage` (unless a
   * receiver implementation is about to re-order persistent messages using `stash()` and `unstash()`).
   */
  def lastSequenceNr: Long = _lastSequenceNr

  /**
   * Returns `lastSequenceNr`.
   */
  def snapshotSequenceNr: Long = lastSequenceNr

  /**
   * INTERNAL API.
   */
  private[persistence] var _currentState: State = recoveryPending

  /**
   * INTERNAL API.
   *
   * Called whenever a message replay succeeds.
   *
   * @param receive the actor's current behavior.
   * @param awaitReplay `awaitReplay` value of the calling `replayStarted` state.
   */
  private[persistence] def onReplaySuccess(receive: Receive, awaitReplay: Boolean): Unit

  /**
   * INTERNAL API.
   *
   * Called whenever a message replay fails.
   *
   * @param receive the actor's current behavior.
   * @param awaitReplay `awaitReplay` value of the calling `replayStarted` state.
   * @param cause failure cause.
   */
  private[persistence] def onReplayFailure(receive: Receive, awaitReplay: Boolean, cause: Throwable): Unit

  /**
   * INTERNAL API.
   */
  private[persistence] val extension = Persistence(context.system)

  /**
   * INTERNAL API.
   */
  private[persistence] lazy val journal = extension.journalFor(persistenceId)

  /**
   * INTERNAL API.
   */
  private[persistence] val receiverStash = createStash()

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundReceive(receive: Receive, message: Any): Unit = {
    _currentState.aroundReceive(receive, message)
  }
}

/**
 * Instructs a persistent actor to recover itself. Recovery will start from a snapshot if the persistent actor has
 * previously saved one or more snapshots and at least one of these snapshots matches the specified
 * `fromSnapshot` criteria. Otherwise, recovery will start from scratch by replaying all journaled
 * messages.
 *
 * If recovery starts from a snapshot, the persistent actor is offered that snapshot with a [[SnapshotOffer]]
 * message, followed by replayed messages, if any, that are younger than the snapshot, up to the
 * specified upper sequence number bound (`toSequenceNr`).
 *
 * @param fromSnapshot criteria for selecting a saved snapshot from which recovery should start. Default
 *                     is latest (= youngest) snapshot.
 * @param toSequenceNr upper sequence number bound (inclusive) for recovery. Default is no upper bound.
 * @param replayMax maximum number of messages to replay. Default is no limit.
 */
@SerialVersionUID(1L)
final case class Recover(fromSnapshot: SnapshotSelectionCriteria = SnapshotSelectionCriteria.Latest, toSequenceNr: Long = Long.MaxValue, replayMax: Long = Long.MaxValue)

object Recover {
  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create() = Recover()

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(toSequenceNr: Long) =
    Recover(toSequenceNr = toSequenceNr)

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(fromSnapshot: SnapshotSelectionCriteria) =
    Recover(fromSnapshot = fromSnapshot)

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(fromSnapshot: SnapshotSelectionCriteria, toSequenceNr: Long) =
    Recover(fromSnapshot, toSequenceNr)

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(fromSnapshot: SnapshotSelectionCriteria, toSequenceNr: Long, replayMax: Long) =
    Recover(fromSnapshot, toSequenceNr, replayMax)
}
