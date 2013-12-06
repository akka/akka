/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.annotation.tailrec

import akka.actor._
import akka.dispatch._

/**
 * An actor that persists (journals) messages of type [[Persistent]]. Messages of other types are not persisted.
 *
 * {{{
 * import akka.persistence.{ Persistent, Processor }
 *
 * class MyProcessor extends Processor {
 *   def receive = {
 *     case Persistent(payload, sequenceNr) => // message has been written to journal
 *     case other                           => // message has not been written to journal
 *   }
 * }
 *
 * val processor = actorOf(Props[MyProcessor], name = "myProcessor")
 *
 * processor ! Persistent("foo")
 * processor ! "bar"
 * }}}
 *
 *
 * During start and restart, persistent messages are replayed to a processor so that it can recover internal
 * state from these messages. New messages sent to a processor during recovery do not interfere with replayed
 * messages, hence applications don't need to wait for a processor to complete its recovery.
 *
 * Automated recovery can be turned off or customized by overriding the [[preStart]] and [[preRestart]] life
 * cycle hooks. If automated recovery is turned off, an application can explicitly recover a processor by
 * sending it a [[Recover]] message.
 *
 * [[Persistent]] messages are assigned sequence numbers that are generated on a per-processor basis. A sequence
 * starts at `1L` and doesn't contain gaps unless a processor (logically) deletes a message
 *
 * During recovery, a processor internally buffers new messages until recovery completes, so that new messages
 * do not interfere with replayed messages. This internal buffer (the ''processor stash'') is isolated from the
 * ''user stash'' inherited by `akka.actor.Stash`. `Processor` implementation classes can therefore use the
 * ''user stash'' for stashing/unstashing both persistent and transient messages.
 *
 * Processors can also store snapshots of internal state by calling [[saveSnapshot]]. During recovery, a saved
 * snapshot is offered to the processor with a [[SnapshotOffer]] message, followed by replayed messages, if any,
 * that are younger than the snapshot. Default is to offer the latest saved snapshot.
 *
 * @see [[UntypedProcessor]]
 * @see [[Recover]]
 * @see [[PersistentBatch]]
 */
trait Processor extends Actor with Stash with StashFactory {
  import JournalProtocol._
  import SnapshotProtocol._

  private val extension = Persistence(context.system)
  private val _processorId = extension.processorId(self)

  import extension.maxBatchSize

  /**
   * Processor state.
   */
  private trait State {
    /**
     * State-specific message handler.
     */
    def aroundReceive(receive: Actor.Receive, message: Any): Unit

    protected def process(receive: Actor.Receive, message: Any) =
      receive.applyOrElse(message, unhandled)

    protected def processPersistent(receive: Actor.Receive, persistent: Persistent) =
      withCurrentPersistent(persistent)(receive.applyOrElse(_, unhandled))
  }

  /**
   * Initial state, waits for `Recover` request, then changes to `recoveryStarted`.
   */
  private val recoveryPending = new State {
    override def toString: String = "recovery pending"

    def aroundReceive(receive: Actor.Receive, message: Any): Unit = message match {
      case Recover(fromSnap, toSnr) ⇒
        _currentState = recoveryStarted
        snapshotStore ! LoadSnapshot(processorId, fromSnap, toSnr)
      case _ ⇒ processorStash.stash()
    }
  }

  /**
   * Processes a loaded snapshot and replayed messages, if any. If processing of the loaded
   * snapshot fails, the exception is thrown immediately. If processing of a replayed message
   * fails, the exception is caught and stored for being thrown later and state is changed to
   * `recoveryFailed`.
   */
  private val recoveryStarted = new State {
    override def toString: String = "recovery started"

    def aroundReceive(receive: Actor.Receive, message: Any) = message match {
      case LoadSnapshotResult(sso, toSnr) ⇒ sso match {
        case Some(SelectedSnapshot(metadata, snapshot)) ⇒
          process(receive, SnapshotOffer(metadata, snapshot))
          journal ! Replay(metadata.sequenceNr + 1L, toSnr, processorId, self)
        case None ⇒
          journal ! Replay(1L, toSnr, processorId, self)
      }
      case ReplaySuccess(maxSnr) ⇒
        _currentState = recoverySucceeded
        _sequenceNr = maxSnr
        processorStash.unstashAll()
      case ReplayFailure(cause) ⇒
        val notification = RecoveryFailure(cause)
        if (receive.isDefinedAt(notification)) process(receive, notification)
        else {
          val errorMsg = s"Replay failure by journal (processor id = [${processorId}])"
          throw new RecoveryFailureException(errorMsg, cause)
        }
      case Replayed(p) ⇒ try { processPersistent(receive, p) } catch {
        case t: Throwable ⇒
          _currentState = recoveryFailed // delay throwing exception to prepareRestart
          _recoveryFailureCause = t
          _recoveryFailureMessage = currentEnvelope
      }
      case r: Recover ⇒ // ignore
      case _          ⇒ processorStash.stash()
    }
  }

  /**
   * Journals and processes new messages, both persistent and transient.
   */
  private val recoverySucceeded = new State {
    override def toString: String = "recovery finished"

    private var batching = false

    def aroundReceive(receive: Actor.Receive, message: Any) = message match {
      case r: Recover      ⇒ // ignore
      case Replayed(p)     ⇒ processPersistent(receive, p) // can occur after unstash from user stash
      case WriteSuccess(p) ⇒ processPersistent(receive, p)
      case WriteFailure(p, cause) ⇒
        val notification = PersistenceFailure(p.payload, p.sequenceNr, cause)
        if (receive.isDefinedAt(notification)) process(receive, notification)
        else {
          val errorMsg = "Processor killed after persistence failure " +
            s"(processor id = [${processorId}], sequence nr = [${p.sequenceNr}], payload class = [${p.payload.getClass.getName}]). " +
            "To avoid killing processors on persistence failure, a processor must handle PersistenceFailure messages."
          throw new ActorKilledException(errorMsg)
        }
      case LoopSuccess(m) ⇒ process(receive, m)
      case WriteBatchSuccess | WriteBatchFailure(_) ⇒
        if (processorBatch.isEmpty) batching = false else journalBatch()
      case p: PersistentRepr ⇒
        addToBatch(p)
        if (!batching || maxBatchSizeReached) journalBatch()
      case pb: PersistentBatch ⇒
        // submit all batched messages before submitting this user batch (isolated)
        if (!processorBatch.isEmpty) journalBatch()
        addToBatch(pb)
        journalBatch()
      case m ⇒
        // submit all batched messages before looping this message
        if (processorBatch.isEmpty) batching = false else journalBatch()
        journal forward Loop(m, self)
    }

    def addToBatch(p: PersistentRepr): Unit =
      processorBatch = processorBatch :+ p.update(processorId = processorId, sequenceNr = nextSequenceNr(), sender = sender)

    def addToBatch(pb: PersistentBatch): Unit =
      pb.persistentReprList.foreach(addToBatch)

    def maxBatchSizeReached: Boolean =
      processorBatch.length >= maxBatchSize

    def journalBatch(): Unit = {
      journal ! WriteBatch(processorBatch, self)
      processorBatch = Vector.empty
      batching = true
    }
  }

  /**
   * Consumes remaining replayed messages and then changes to `prepareRestart`. The
   * message that caused the exception during replay, is re-added to the mailbox and
   * re-received in `prepareRestart`.
   */
  private val recoveryFailed = new State {
    override def toString: String = "recovery failed"

    def aroundReceive(receive: Actor.Receive, message: Any) = message match {
      case ReplayFailure(_) ⇒
        replayCompleted()
        // journal couldn't tell the maximum stored sequence number, hence the next
        // replay must be a full replay (up to the highest stored sequence number)
        _lastSequenceNr = Long.MaxValue
      case ReplaySuccess(_) ⇒ replayCompleted()
      case Replayed(p)      ⇒ updateLastSequenceNr(p)
      case r: Recover       ⇒ // ignore
      case _                ⇒ processorStash.stash()
    }

    def replayCompleted(): Unit = {
      _currentState = prepareRestart
      mailbox.enqueueFirst(self, _recoveryFailureMessage)
    }
  }

  /**
   * Re-receives the replayed message that causes an exception during replay and throws
   * that exception.
   */
  private val prepareRestart = new State {
    override def toString: String = "prepare restart"

    def aroundReceive(receive: Actor.Receive, message: Any) = message match {
      case Replayed(_) ⇒ throw _recoveryFailureCause
      case _           ⇒ // ignore
    }
  }

  private var processorBatch = Vector.empty[PersistentRepr]

  private var _sequenceNr: Long = 0L
  private var _lastSequenceNr: Long = 0L

  private var _currentPersistent: Persistent = _
  private var _currentState: State = recoveryPending

  private var _recoveryFailureCause: Throwable = _
  private var _recoveryFailureMessage: Envelope = _

  private lazy val journal = extension.journalFor(processorId)
  private lazy val snapshotStore = extension.snapshotStoreFor(processorId)

  /**
   * Processor id. Defaults to this processor's path and can be overridden.
   */
  def processorId: String = _processorId

  /**
   * Highest received sequence number so far or `0L` if this processor hasn't received
   * a persistent message yet. Usually equal to the sequence number of `currentPersistentMessage`
   * (unless a processor implementation is about to re-order persistent messages using
   * `stash()` and `unstash()`).
   */
  def lastSequenceNr: Long = _lastSequenceNr

  /**
   * Returns `true` if this processor is currently recovering.
   */
  def recoveryRunning: Boolean =
    _currentState == recoveryStarted ||
      _currentState == prepareRestart

  /**
   * Returns `true` if this processor has successfully finished recovery.
   */
  def recoveryFinished: Boolean =
    _currentState == recoverySucceeded

  /**
   * Returns the current persistent message if there is one.
   */
  implicit def currentPersistentMessage: Option[Persistent] = Option(_currentPersistent)

  /**
   * Marks a persistent message, identified by `sequenceNr`, as deleted. A message marked as deleted is
   * not replayed during recovery. This method is usually called inside `preRestartProcessor` when a
   * persistent message caused an exception. Processors that want to re-receive that persistent message
   * during recovery should not call this method.
   *
   * @param sequenceNr sequence number of the persistent message to be deleted.
   */
  def deleteMessage(sequenceNr: Long): Unit = {
    deleteMessage(sequenceNr, false)
  }

  /**
   * Deletes a persistent message identified by `sequenceNr`. If `permanent` is set to `false`,
   * the persistent message is marked as deleted in the journal, otherwise it is permanently
   * deleted from the journal. A deleted message is not replayed during recovery. This method
   * is usually called inside `preRestartProcessor` when a persistent message caused an exception.
   * Processors that want to re-receive that persistent message during recovery should not call
   * this method.
   *
   * Later extensions may also allow a replay of messages that have been marked as deleted which can
   * be useful in debugging environments.
   *
   * @param sequenceNr sequence number of the persistent message to be deleted.
   * @param permanent if `false`, the message is marked as deleted, otherwise it is permanently deleted.
   */
  def deleteMessage(sequenceNr: Long, permanent: Boolean): Unit = {
    journal ! Delete(processorId, sequenceNr, sequenceNr, permanent)
  }

  /**
   * Marks all persistent messages with sequence numbers less than or equal `toSequenceNr` as deleted.
   *
   * @param toSequenceNr upper sequence number bound of persistent messages to be deleted.
   */
  def deleteMessages(toSequenceNr: Long): Unit = {
    deleteMessages(toSequenceNr, false)
  }

  /**
   * Deletes all persistent messages with sequence numbers less than or equal `toSequenceNr`. If `permanent`
   * is set to `false`, the persistent messages are marked as deleted in the journal, otherwise
   * they permanently deleted from the journal.
   *
   * Later extensions may also allow a replay of messages that have been marked as deleted which can
   * be useful in debugging environments.
   *
   * @param toSequenceNr upper sequence number bound of persistent messages to be deleted.
   * @param permanent if `false`, the message is marked as deleted, otherwise it is permanently deleted.
   */
  def deleteMessages(toSequenceNr: Long, permanent: Boolean): Unit = {
    journal ! Delete(processorId, 1L, toSequenceNr, permanent)
  }

  /**
   * Saves a `snapshot` of this processor's state. If saving succeeds, this processor will receive a
   * [[SaveSnapshotSuccess]] message, otherwise a [[SaveSnapshotFailure]] message.
   */
  def saveSnapshot(snapshot: Any): Unit = {
    snapshotStore ! SaveSnapshot(SnapshotMetadata(processorId, lastSequenceNr), snapshot)
  }

  /**
   * Deletes a snapshot identified by `sequenceNr` and `timestamp`.
   */
  def deleteSnapshot(sequenceNr: Long, timestamp: Long): Unit = {
    snapshotStore ! DeleteSnapshot(SnapshotMetadata(processorId, sequenceNr, timestamp))
  }

  /**
   * Deletes all snapshots matching `criteria`.
   */
  def deleteSnapshots(criteria: SnapshotSelectionCriteria): Unit = {
    snapshotStore ! DeleteSnapshots(processorId, criteria)
  }

  /**
   * INTERNAL API.
   */
  protected[persistence] def withCurrentPersistent(persistent: Persistent)(body: Persistent ⇒ Unit): Unit = try {
    _currentPersistent = persistent
    updateLastSequenceNr(persistent)
    body(persistent)
  } finally _currentPersistent = null

  /**
   * INTERNAL API.
   */
  protected[persistence] def updateLastSequenceNr(persistent: Persistent) {
    if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundReceive(receive: Actor.Receive, message: Any): Unit = {
    _currentState.aroundReceive(receive, message)
  }

  /**
   * INTERNAL API.
   */
  final override protected[akka] def aroundPreStart(): Unit = {
    try preStart() finally super.preStart()
  }

  /**
   * INTERNAL API.
   */
  final override protected[akka] def aroundPostStop(): Unit = {
    try unstashAll(unstashFilterPredicate) finally postStop()
  }

  /**
   * INTERNAL API.
   */
  final override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    try {
      processorStash.prepend(processorBatch.map(p ⇒ Envelope(p, p.sender, context.system)))
      processorStash.unstashAll()
      unstashAll(unstashFilterPredicate)
    } finally {
      message match {
        case Some(WriteSuccess(m)) ⇒ preRestartDefault(reason, Some(m))
        case Some(LoopSuccess(m))  ⇒ preRestartDefault(reason, Some(m))
        case Some(Replayed(m))     ⇒ preRestartDefault(reason, Some(m))
        case mo                    ⇒ preRestartDefault(reason, None)
      }
    }
  }

  /**
   * User-overridable callback. Called when a processor is started. Default implementation sends
   * a `Recover()` to `self`.
   */
  @throws(classOf[Exception])
  override def preStart(): Unit = {
    self ! Recover()
  }

  /**
   * User-overridable callback. Called before a processor is restarted. Default implementation sends
   * a `Recover(lastSequenceNr)` message to `self` if `message` is defined, `Recover() otherwise`.
   */
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    message match {
      case Some(_) ⇒ self ! Recover(toSequenceNr = lastSequenceNr)
      case None    ⇒ self ! Recover()
    }
  }

  /**
   * Calls [[preRestart]] and then `super.preRestart()`. If processor implementation classes want to
   * opt out from stopping child actors, they should override this method and call [[preRestart]] only.
   */
  def preRestartDefault(reason: Throwable, message: Option[Any]): Unit = {
    try preRestart(reason, message) finally super.preRestart(reason, message)
  }

  private def nextSequenceNr(): Long = {
    _sequenceNr += 1L
    _sequenceNr
  }

  // -----------------------------------------------------
  //  Processor-internal stash
  // -----------------------------------------------------

  private val unstashFilterPredicate: Any ⇒ Boolean = {
    case _: WriteSuccess ⇒ false
    case _: Replayed     ⇒ false
    case _               ⇒ true
  }

  private val processorStash = createStash()

  private def currentEnvelope: Envelope =
    context.asInstanceOf[ActorCell].currentMessage
}

/**
 * Sent to a [[Processor]] when a journal failed to write a [[Persistent]] message. If
 * not handled, an `akka.actor.ActorKilledException` is thrown by that processor.
 *
 * @param payload payload of the persistent message.
 * @param sequenceNr sequence number of the persistent message.
 * @param cause failure cause.
 */
case class PersistenceFailure(payload: Any, sequenceNr: Long, cause: Throwable)

/**
 * Java API: an actor that persists (journals) messages of type [[Persistent]]. Messages of other types
 * are not persisted.
 *
 * {{{
 * import akka.persistence.Persistent;
 * import akka.persistence.Processor;
 *
 * class MyProcessor extends UntypedProcessor {
 *     public void onReceive(Object message) throws Exception {
 *         if (message instanceof Persistent) {
 *             // message has been written to journal
 *             Persistent persistent = (Persistent)message;
 *             Object payload = persistent.payload();
 *             Long sequenceNr = persistent.sequenceNr();
 *             // ...
 *         } else {
 *             // message has not been written to journal
 *         }
 *     }
 * }
 *
 * // ...
 *
 * ActorRef processor = getContext().actorOf(Props.create(MyProcessor.class), "myProcessor");
 *
 * processor.tell(Persistent.create("foo"), null);
 * processor.tell("bar", null);
 * }}}
 *
 * During start and restart, persistent messages are replayed to a processor so that it can recover internal
 * state from these messages. New messages sent to a processor during recovery do not interfere with replayed
 * messages, hence applications don't need to wait for a processor to complete its recovery.
 *
 * Automated recovery can be turned off or customized by overriding the [[preStart]] and [[preRestart]] life
 * cycle hooks. If automated recovery is turned off, an application can explicitly recover a processor by
 * sending it a [[Recover]] message.
 *
 * [[Persistent]] messages are assigned sequence numbers that are generated on a per-processor basis. A sequence
 * starts at `1L` and doesn't contain gaps unless a processor (logically) deletes a message.
 *
 * During recovery, a processor internally buffers new messages until recovery completes, so that new messages
 * do not interfere with replayed messages. This internal buffer (the ''processor stash'') is isolated from the
 * ''user stash'' inherited by `akka.actor.Stash`. `Processor` implementation classes can therefore use the
 * ''user stash'' for stashing/unstashing both persistent and transient messages.
 *
 * Processors can also store snapshots of internal state by calling [[saveSnapshot]]. During recovery, a saved
 * snapshot is offered to the processor with a [[SnapshotOffer]] message, followed by replayed messages, if any,
 * that are younger than the snapshot. Default is to offer the latest saved snapshot.
 *
 * @see [[Processor]]
 * @see [[Recover]]
 * @see [[PersistentBatch]]
 */
abstract class UntypedProcessor extends UntypedActor with Processor {
  /**
   * Java API. returns the current persistent message or `null` if there is none.
   */
  def getCurrentPersistentMessage = currentPersistentMessage.getOrElse(null)
}
