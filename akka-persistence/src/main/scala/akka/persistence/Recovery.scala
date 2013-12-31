/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.AkkaException
import akka.actor._
import akka.dispatch.Envelope
import akka.persistence.JournalProtocol._
import akka.persistence.SnapshotProtocol.LoadSnapshotResult

/**
 * Recovery state machine that loads snapshots and replays messages.
 *
 * @see [[Processor]]
 * @see [[Replicator]]
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
      receive.applyOrElse(message, unhandled)

    protected def processPersistent(receive: Receive, persistent: Persistent) =
      withCurrentPersistent(persistent)(receive.applyOrElse(_, unhandled))

    protected def updateLastSequenceNr(persistent: Persistent): Unit =
      if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr

    def updateLastSequenceNr(value: Long): Unit =
      _lastSequenceNr = value

    protected def withCurrentPersistent(persistent: Persistent)(body: Persistent ⇒ Unit): Unit = try {
      _currentPersistent = persistent
      updateLastSequenceNr(persistent)
      body(persistent)
    } finally _currentPersistent = null

    protected def recordFailure(cause: Throwable): Unit = {
      _recoveryFailureCause = cause
      _recoveryFailureMessage = context.asInstanceOf[ActorCell].currentMessage
    }
  }

  /**
   * INTERNAL API.
   *
   * Initial state, waits for `Recover` request, submit a `LoadSnapshot` request to the journal
   * and changes to `recoveryStarted` state.
   */
  private[persistence] val recoveryPending = new State {
    override def toString: String = "recovery pending"

    def aroundReceive(receive: Receive, message: Any): Unit = message match {
      case Recover(fromSnap, toSnr) ⇒
        _currentState = recoveryStarted(awaitReplay = true)
        loadSnapshot(snapshotterId, fromSnap, toSnr)
      case _ ⇒ receiverStash.stash()
    }
  }

  /**
   * INTERNAL API.
   *
   * Processes a loaded snapshot and replayed messages, if any. A loaded snapshot is offered with
   * a `SnapshotOffer` message to the actor's current behavior, replayed messages as [[Persistent]]
   * messages. If processing of a replayed message fails, the exception is caught and stored for
   * being thrown later and state is changed to `recoveryFailed`. If replay succeeds the
   * `onReplaySuccess` method is called, otherwise `onReplayFailure`.
   *
   * @param awaitReplay if `true` processing of further messages will be delayed until recovery
   *                    completed, otherwise, the actor's behavior is invoked immediately with
   *                    these messages.
   */
  private[persistence] def recoveryStarted(awaitReplay: Boolean) = new State {
    override def toString: String = s"recovery started (awaitReplay = [${awaitReplay}])"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case r: Recover ⇒ // ignore
      case LoadSnapshotResult(sso, toSnr) ⇒
        sso.foreach {
          case SelectedSnapshot(metadata, snapshot) ⇒
            updateLastSequenceNr(metadata.sequenceNr)
            process(receive, SnapshotOffer(metadata, snapshot))
        }
        journal ! Replay(lastSequenceNr + 1L, toSnr, processorId, self)
      case Replayed(p) ⇒ try { processPersistent(receive, p) } catch {
        case t: Throwable ⇒
          _currentState = replayFailed // delay throwing exception to prepareRestart
          recordFailure(t)
      }
      case ReplaySuccess(maxSnr) ⇒ onReplaySuccess(receive, awaitReplay, maxSnr)
      case ReplayFailure(cause)  ⇒ onReplayFailure(receive, awaitReplay, cause)
      case other ⇒
        if (awaitReplay) receiverStash.stash() else process(receive, other)
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
      case ReplayFailure(_) ⇒
        replayCompleted()
        // journal couldn't tell the maximum stored sequence number, hence the next
        // replay must be a full replay (up to the highest stored sequence number)
        updateLastSequenceNr(Long.MaxValue)
      case ReplaySuccess(_) ⇒ replayCompleted()
      case Replayed(p)      ⇒ updateLastSequenceNr(p)
      case r: Recover       ⇒ // ignore
      case _                ⇒ receiverStash.stash()
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
      case Replayed(_) ⇒ throw _recoveryFailureCause
      case _           ⇒ // ignore
    }
  }

  private var _recoveryFailureCause: Throwable = _
  private var _recoveryFailureMessage: Envelope = _

  private var _lastSequenceNr: Long = 0L
  private var _currentPersistent: Persistent = _

  /**
   * Id of the processor for which messages should be replayed.
   */
  def processorId: String

  /**
   * Returns the current persistent message if there is any.
   */
  implicit def currentPersistentMessage: Option[Persistent] = Option(_currentPersistent)

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
   * @param awaitReplay `awaitReplay` value of the calling `recoveryStarted` state.
   * @param maxSnr highest stored sequence number.
   */
  private[persistence] def onReplaySuccess(receive: Receive, awaitReplay: Boolean, maxSnr: Long): Unit

  /**
   * INTERNAL API.
   *
   * Called whenever a message replay fails.
   *
   * @param receive the actor's current behavior.
   * @param awaitReplay `awaitReplay` value of the calling `recoveryStarted` state.
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
  private[persistence] lazy val journal = extension.journalFor(processorId)

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
 * Instructs a processor to recover itself. Recovery will start from a snapshot if the processor has
 * previously saved one or more snapshots and at least one of these snapshots matches the specified
 * `fromSnapshot` criteria. Otherwise, recovery will start from scratch by replaying all journaled
 * messages.
 *
 * If recovery starts from a snapshot, the processor is offered that snapshot with a [[SnapshotOffer]]
 * message, followed by replayed messages, if any, that are younger than the snapshot, up to the
 * specified upper sequence number bound (`toSequenceNr`).
 *
 * @param fromSnapshot criteria for selecting a saved snapshot from which recovery should start. Default
 *                     is latest (= youngest) snapshot.
 * @param toSequenceNr upper sequence number bound (inclusive) for recovery. Default is no upper bound.
 */
@SerialVersionUID(1L)
case class Recover(fromSnapshot: SnapshotSelectionCriteria = SnapshotSelectionCriteria.Latest, toSequenceNr: Long = Long.MaxValue)

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
}

/**
 * Sent to a [[Processor]] after failed recovery. If not handled, a
 * [[RecoveryFailureException]] is thrown by that processor.
 */
@SerialVersionUID(1L)
case class RecoveryFailure(cause: Throwable)

/**
 * Thrown by a [[Processor]] if a journal failed to replay all requested messages.
 */
@SerialVersionUID(1L)
case class RecoveryFailureException(message: String, cause: Throwable) extends AkkaException(message, cause)

