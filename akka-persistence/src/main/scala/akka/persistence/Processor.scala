/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

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
trait Processor extends Actor with Recovery {
  import JournalProtocol._

  /**
   * Journals and processes new messages, both persistent and transient.
   */
  private val processing = new State {
    override def toString: String = "recovery finished"

    private var batching = false

    def aroundReceive(receive: Receive, message: Any) = message match {
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
      processorBatch.length >= extension.maxBatchSize

    def journalBatch(): Unit = {
      journal ! WriteBatch(processorBatch, self)
      processorBatch = Vector.empty
      batching = true
    }
  }

  /**
   * INTERNAL API.
   *
   * Initializes this processor's sequence number generator and switches to live message processing.
   */
  private[persistence] def onReplaySuccess(receive: Receive, awaitReplay: Boolean, maxSnr: Long): Unit = {
    _currentState = processing
    sequenceNr = maxSnr
    if (awaitReplay) receiverStash.unstashAll()
  }

  /**
   * INTERNAL API.
   *
   * Invokes this processor's behavior with a `RecoveryFailure` message if handled, otherwise throws a
   * `ReplayFailureException`.
   */
  private[persistence] def onReplayFailure(receive: Receive, awaitReplay: Boolean, cause: Throwable): Unit = {
    val notification = RecoveryFailure(cause)
    if (receive.isDefinedAt(notification)) {
      receive(notification)
    } else {
      val errorMsg = s"Replay failure by journal (processor id = [${processorId}])"
      throw new RecoveryFailureException(errorMsg, cause)
    }
  }

  private val _processorId = extension.processorId(self)

  private var processorBatch = Vector.empty[PersistentRepr]
  private var sequenceNr: Long = 0L

  /**
   * Processor id. Defaults to this processor's path and can be overridden.
   */
  def processorId: String = _processorId

  /**
   * Returns `processorId`.
   */
  def snapshotterId: String = processorId

  /**
   * Returns `true` if this processor is currently recovering.
   */
  def recoveryRunning: Boolean =
    _currentState != processing

  /**
   * Returns `true` if this processor has successfully finished recovery.
   */
  def recoveryFinished: Boolean =
    _currentState == processing

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
      receiverStash.prepend(processorBatch.map(p ⇒ Envelope(p, p.sender, context.system)))
      receiverStash.unstashAll()
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
    sequenceNr += 1L
    sequenceNr
  }

  private val unstashFilterPredicate: Any ⇒ Boolean = {
    case _: WriteSuccess ⇒ false
    case _: Replayed     ⇒ false
    case _               ⇒ true
  }
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
