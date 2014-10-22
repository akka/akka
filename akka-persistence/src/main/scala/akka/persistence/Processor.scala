/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.AkkaException
import akka.actor._
import akka.dispatch._
import java.util.concurrent.atomic.AtomicInteger

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
@deprecated("Processor will be removed. Instead extend `akka.persistence.PersistentActor` and use it's `persistAsync(command)(callback)` method to get equivalent semantics.", since = "2.3.4")
trait Processor extends ProcessorImpl {
  /**
   * Persistence id. Defaults to this persistent-actors's path and can be overridden.
   */
  override def persistenceId: String = processorId
}

/**
 * INTERNAL API
 */
private[akka] object ProcessorImpl {
  // ok to wrap around (2*Int.MaxValue restarts will not happen within a journal roundtrip)
  private val instanceIdCounter = new AtomicInteger
}

/** INTERNAL API */
@deprecated("Processor will be removed. Instead extend `akka.persistence.PersistentActor` and use it's `persistAsync(command)(callback)` method to get equivalent semantics.", since = "2.3.4")
private[akka] trait ProcessorImpl extends Actor with Recovery {
  // TODO: remove Processor in favor of PersistentActor #15230

  import JournalProtocol._

  private[persistence] val instanceId: Int = ProcessorImpl.instanceIdCounter.incrementAndGet()

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
        onRecoveryCompleted(receive)
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
      case r: Recover                                ⇒ // ignore
      case ReplayedMessage(p)                        ⇒ processPersistent(receive, p) // can occur after unstash from user stash
      case WriteMessageSuccess(p: PersistentRepr, _) ⇒ processPersistent(receive, p)
      case WriteMessageSuccess(r: Resequenceable, _) ⇒ process(receive, r)
      case WriteMessageFailure(p, cause, _)          ⇒ process(receive, PersistenceFailure(p.payload, p.sequenceNr, cause))
      case LoopMessageSuccess(m, _)                  ⇒ process(receive, m)
      case WriteMessagesSuccessful | WriteMessagesFailed(_) ⇒
        if (processorBatch.isEmpty) batching = false else journalBatch()
      case p: PersistentRepr ⇒
        addToBatch(p)
        if (!batching || maxBatchSizeReached) journalBatch()
      case n: NonPersistentRepr ⇒
        addToBatch(n)
        if (!batching || maxBatchSizeReached) journalBatch()
      case pb: PersistentBatch ⇒
        // submit all batched messages before submitting this user batch (isolated)
        if (!processorBatch.isEmpty) journalBatch()
        addToBatch(pb)
        journalBatch()
      case m ⇒
        // submit all batched messages before looping this message
        if (processorBatch.isEmpty) batching = false else journalBatch()
        journal forward LoopMessage(m, self, instanceId)
    }

    def addToBatch(p: Resequenceable): Unit = p match {
      case p: PersistentRepr ⇒
        processorBatch = processorBatch :+ p.update(persistenceId = persistenceId, sequenceNr = nextSequenceNr(), sender = sender())
      case r ⇒
        processorBatch = processorBatch :+ r
    }

    def addToBatch(pb: PersistentBatch): Unit =
      pb.batch.foreach(addToBatch)

    def maxBatchSizeReached: Boolean =
      processorBatch.length >= extension.settings.journal.maxMessageBatchSize

    def journalBatch(): Unit = {
      flushJournalBatch()
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
    journal ! ReadHighestSequenceNr(lastSequenceNr, persistenceId, self)
  }

  /**
   * INTERNAL API.
   */
  private[persistence] def onReplayFailure(receive: Receive, awaitReplay: Boolean, cause: Throwable): Unit =
    onRecoveryFailure(receive, cause)

  /**
   * Invokes this processor's behavior with a `RecoveryFailure` message.
   */
  private def onRecoveryFailure(receive: Receive, cause: Throwable): Unit =
    receive.applyOrElse(RecoveryFailure(cause), unhandled)

  /**
   * Invokes this processor's behavior with a `RecoveryFinished` message.
   */
  private def onRecoveryCompleted(receive: Receive): Unit =
    receive.applyOrElse(RecoveryCompleted, unhandled)

  private val _persistenceId = extension.persistenceId(self)

  private var processorBatch = Vector.empty[Resequenceable]
  private var sequenceNr: Long = 0L

  /**
   * Processor id. Defaults to this processor's path and can be overridden.
   */
  @deprecated("Override `persistenceId: String` instead. Processor will be removed.", since = "2.3.4")
  override def processorId: String = _persistenceId // TODO: remove processorId

  /**
   * Returns `persistenceId`.
   */
  def snapshotterId: String = persistenceId

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
  @deprecated("deleteMessage(sequenceNr) will be removed. Instead, validate before persist, and use deleteMessages for pruning.", since = "2.3.4")
  def deleteMessage(sequenceNr: Long): Unit = {
    deleteMessage(sequenceNr, permanent = false)
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
  @deprecated("deleteMessage(sequenceNr) will be removed. Instead, validate before persist, and use deleteMessages for pruning.", since = "2.3.4")
  def deleteMessage(sequenceNr: Long, permanent: Boolean): Unit = {
    journal ! DeleteMessages(List(PersistentIdImpl(persistenceId, sequenceNr)), permanent)
  }

  /**
   * Permanently deletes all persistent messages with sequence numbers less than or equal `toSequenceNr`.
   *
   * @param toSequenceNr upper sequence number bound of persistent messages to be deleted.
   */
  def deleteMessages(toSequenceNr: Long): Unit = {
    deleteMessages(toSequenceNr, permanent = true)
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
    journal ! DeleteMessagesTo(persistenceId, toSequenceNr, permanent)
  }

  /**
   * INTERNAL API
   */
  private[akka] def flushJournalBatch(): Unit = {
    journal ! WriteMessages(processorBatch, self, instanceId)
    processorBatch = Vector.empty
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundPostStop(): Unit = {
    // calls `super.aroundPostStop` to allow Processor to be used as a stackable modification
    try unstashAll(unstashFilterPredicate) finally super.aroundPostStop()
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    try {
      receiverStash.prepend(processorBatch.map(p ⇒ Envelope(p, p.sender, context.system)))
      receiverStash.unstashAll()
      unstashAll(unstashFilterPredicate)
    } finally {
      message match {
        case Some(WriteMessageSuccess(m, _)) ⇒ super.aroundPreRestart(reason, Some(m))
        case Some(LoopMessageSuccess(m, _))  ⇒ super.aroundPreRestart(reason, Some(m))
        case Some(ReplayedMessage(m))        ⇒ super.aroundPreRestart(reason, Some(m))
        case mo                              ⇒ super.aroundPreRestart(reason, None)
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
    super.preRestart(reason, message)
    message match {
      case Some(_) ⇒ self ! Recover(toSequenceNr = lastSequenceNr)
      case None    ⇒ self ! Recover()
    }
  }

  override def unhandled(message: Any): Unit = {
    message match {
      case RecoveryCompleted ⇒ // mute
      case RecoveryFailure(cause) ⇒
        val errorMsg = s"Processor killed after recovery failure (persisten id = [${persistenceId}]). " +
          "To avoid killing processors on recovery failure, a processor must handle RecoveryFailure messages. " +
          "RecoveryFailure was caused by: " + cause
        throw new ActorKilledException(errorMsg)
      case PersistenceFailure(payload, sequenceNumber, cause) ⇒
        val errorMsg = "Processor killed after persistence failure " +
          s"(persistent id = [${persistenceId}], sequence nr = [${sequenceNumber}], payload class = [${payload.getClass.getName}]). " +
          "To avoid killing processors on persistence failure, a processor must handle PersistenceFailure messages. " +
          "PersistenceFailure was caused by: " + cause
        throw new ActorKilledException(errorMsg)
      case m ⇒ super.unhandled(m)
    }
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
 * highest sequence number. If not handled, the prossor will be stopped.
 */
@SerialVersionUID(1L)
case class RecoveryFailure(cause: Throwable)

abstract class RecoveryCompleted
/**
 * Sent to a [[Processor]] when the journal replay has been finished.
 */
@SerialVersionUID(1L)
case object RecoveryCompleted extends RecoveryCompleted {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

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
@deprecated("UntypedProcessor will be removed. Instead extend `akka.persistence.UntypedPersistentActor` and use it's `persistAsync(command)(callback)` method to get equivalent semantics.", since = "2.3.4")
abstract class UntypedProcessor extends UntypedActor with Processor

/**
 * Java API: compatible with lambda expressions
 *
 * An actor that persists (journals) messages of type [[Persistent]]. Messages of other types
 * are not persisted.
 * <p/>
 * Example:
 * <pre>
 * class MyProcessor extends AbstractProcessor {
 *   public MyProcessor() {
 *     receive(ReceiveBuilder.
 *       match(Persistent.class, p -> {
 *         Object payload = p.payload();
 *         Long sequenceNr = p.sequenceNr();
 *                 // ...
 *       }).build()
 *     );
 *   }
 * }
 *
 * // ...
 *
 * ActorRef processor = context().actorOf(Props.create(MyProcessor.class), "myProcessor");
 *
 * processor.tell(Persistent.create("foo"), null);
 * processor.tell("bar", null);
 * </pre>
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
@deprecated("AbstractProcessor will be removed. Instead extend `akka.persistence.AbstractPersistentActor` and use it's `persistAsync(command)(callback)` method to get equivalent semantics.", since = "2.3.4")
abstract class AbstractProcessor extends AbstractActor with Processor
