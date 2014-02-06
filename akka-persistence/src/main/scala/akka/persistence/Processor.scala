/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.AkkaException
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
   * Processes the highest stored sequence number response from the journal and then switches
   * to `processing` state.
   */
  private val initializing = new State {
    override def toString: String = "initializing"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case ReadHighestSequenceNrSuccess(highest) ⇒
        _currentState = processing
        sequenceNr = highest
        receiverStash.unstashAll()
      case ReadHighestSequenceNrFailure(cause) ⇒
        onRecoveryFailure(receive, cause)
      case other ⇒
        receiverStash.stash()
    }
  }

  /**
   * Journals and processes new messages, both persistent and transient.
   */
  private val processing = new State {
    override def toString: String = "processing"

    private var batching = false

    def aroundReceive(receive: Receive, message: Any) = message match {
      case r: Recover             ⇒ // ignore
      case ReplayedMessage(p)     ⇒ processPersistent(receive, p) // can occur after unstash from user stash
      case WriteMessageSuccess(p) ⇒ processPersistent(receive, p)
      case WriteMessageFailure(p, cause) ⇒
        val notification = PersistenceFailure(p.payload, p.sequenceNr, cause)
        if (receive.isDefinedAt(notification)) process(receive, notification)
        else {
          val errorMsg = "Processor killed after persistence failure " +
            s"(processor id = [${processorId}], sequence nr = [${p.sequenceNr}], payload class = [${p.payload.getClass.getName}]). " +
            "To avoid killing processors on persistence failure, a processor must handle PersistenceFailure messages."
          throw new ActorKilledException(errorMsg)
        }
      case LoopMessageSuccess(m) ⇒ process(receive, m)
      case WriteMessagesSuccess | WriteMessagesFailure(_) ⇒
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
        journal forward LoopMessage(m, self)
    }

    def addToBatch(p: PersistentRepr): Unit =
      processorBatch = processorBatch :+ p.update(processorId = processorId, sequenceNr = nextSequenceNr(), sender = sender)

    def addToBatch(pb: PersistentBatch): Unit =
      pb.persistentReprList.foreach(addToBatch)

    def maxBatchSizeReached: Boolean =
      processorBatch.length >= extension.settings.journal.maxMessageBatchSize

    def journalBatch(): Unit = {
      journal ! WriteMessages(processorBatch, self)
      processorBatch = Vector.empty
      batching = true
    }
  }

  /**
   * INTERNAL API.
   *
   * Switches to `initializing` state and requests the highest stored sequence number from the journal.
   */
  private[persistence] def onReplaySuccess(receive: Receive, awaitReplay: Boolean): Unit = {
    _currentState = initializing
    journal ! ReadHighestSequenceNr(lastSequenceNr, processorId, self)
  }

  /**
   * INTERNAL API.
   */
  private[persistence] def onReplayFailure(receive: Receive, awaitReplay: Boolean, cause: Throwable): Unit =
    onRecoveryFailure(receive, cause)

  /**
   * Invokes this processor's behavior with a `RecoveryFailure` message, if handled, otherwise throws a
   * `RecoveryFailureException`.
   */
  private def onRecoveryFailure(receive: Receive, cause: Throwable): Unit = {
    val notification = RecoveryFailure(cause)
    if (receive.isDefinedAt(notification)) {
      receive(notification)
    } else {
      val errorMsg = s"Recovery failure by journal (processor id = [${processorId}])"
      throw new RecoveryException(errorMsg, cause)
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
   * @param sequenceNr sequence number of the persistent message to be deleted.
   * @param permanent if `false`, the message is marked as deleted, otherwise it is permanently deleted.
   */
  def deleteMessage(sequenceNr: Long, permanent: Boolean): Unit = {
    journal ! DeleteMessages(List(PersistentIdImpl(processorId, sequenceNr)), permanent)
  }

  /**
   * Permanently deletes all persistent messages with sequence numbers less than or equal `toSequenceNr`.
   *
   * @param toSequenceNr upper sequence number bound of persistent messages to be deleted.
   */
  def deleteMessages(toSequenceNr: Long): Unit = {
    deleteMessages(toSequenceNr, true)
  }

  /**
   * Deletes all persistent messages with sequence numbers less than or equal `toSequenceNr`. If `permanent`
   * is set to `false`, the persistent messages are marked as deleted in the journal, otherwise
   * they permanently deleted from the journal.
   *
   * @param toSequenceNr upper sequence number bound of persistent messages to be deleted.
   * @param permanent if `false`, the message is marked as deleted, otherwise it is permanently deleted.
   */
  def deleteMessages(toSequenceNr: Long, permanent: Boolean): Unit = {
    journal ! DeleteMessagesTo(processorId, toSequenceNr, permanent)
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
        case Some(WriteMessageSuccess(m)) ⇒ preRestartDefault(reason, Some(m))
        case Some(LoopMessageSuccess(m))  ⇒ preRestartDefault(reason, Some(m))
        case Some(ReplayedMessage(m))     ⇒ preRestartDefault(reason, Some(m))
        case mo                           ⇒ preRestartDefault(reason, None)
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
    case _: WriteMessageSuccess ⇒ false
    case _: ReplayedMessage     ⇒ false
    case _                      ⇒ true
  }
}

/**
 * Sent to a [[Processor]] if a journal fails to write a [[Persistent]] message. If
 * not handled, an `akka.actor.ActorKilledException` is thrown by that processor.
 *
 * @param payload payload of the persistent message.
 * @param sequenceNr sequence number of the persistent message.
 * @param cause failure cause.
 */
@SerialVersionUID(1L)
case class PersistenceFailure(payload: Any, sequenceNr: Long, cause: Throwable)

/**
 * Sent to a [[Processor]] if a journal fails to replay messages or fetch that processor's
 * highest sequence number. If not handled, a [[RecoveryException]] is thrown by that
 * processor.
 */
@SerialVersionUID(1L)
case class RecoveryFailure(cause: Throwable)

/**
 * Thrown by a [[Processor]] if a journal fails to replay messages or fetch that processor's
 * highest sequence number. This exception is only thrown if that processor doesn't handle
 * [[RecoveryFailure]] messages.
 */
@SerialVersionUID(1L)
case class RecoveryException(message: String, cause: Throwable) extends AkkaException(message, cause)

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
abstract class UntypedProcessor extends UntypedActor with Processor
