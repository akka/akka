/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.AkkaException
import akka.actor._
import akka.dispatch._

/**
 * Thrown by a [[Processor]] if a journal failed to replay all requested messages.
 */
@SerialVersionUID(1L)
case class ReplayFailureException(message: String, cause: Throwable) extends AkkaException(message, cause)

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
 */
trait Processor extends Actor with Stash {
  import JournalProtocol._
  import SnapshotProtocol._

  private val extension = Persistence(context.system)
  private val _processorId = extension.processorId(self)

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

    protected def processPersistent(receive: Actor.Receive, persistent: Persistent) = try {
      _currentPersistent = persistent
      updateLastSequenceNr(persistent)
      receive.applyOrElse(persistent, unhandled)
    } finally _currentPersistent = null

    protected def updateLastSequenceNr(persistent: Persistent) {
      if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr
    }
  }

  /**
   * Initial state, waits for `Recover` request, then changes to `recoveryStarted`.
   */
  private val recoveryPending = new State {
    override def toString: String = "recovery pending"

    def aroundReceive(receive: Actor.Receive, message: Any): Unit = message match {
      case Recover(fromSnap, toSnr) ⇒ {
        _currentState = recoveryStarted
        snapshotStore ! LoadSnapshot(processorId, fromSnap, toSnr)
      }
      case _ ⇒ stashInternal()
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
        case Some(SelectedSnapshot(metadata, snapshot)) ⇒ {
          process(receive, SnapshotOffer(metadata, snapshot))
          journal ! Replay(metadata.sequenceNr + 1L, toSnr, processorId, self)
        } case None ⇒ {
          journal ! Replay(1L, toSnr, processorId, self)
        }
      }
      case ReplaySuccess(maxSnr) ⇒ {
        _currentState = recoverySucceeded
        _sequenceNr = maxSnr
        unstashAllInternal()
      }
      case ReplayFailure(cause) ⇒ {
        val errorMsg = s"Replay failure by journal (processor id = [${processorId}])"
        throw new ReplayFailureException(errorMsg, cause)
      }
      case Replayed(p) ⇒ try { processPersistent(receive, p) } catch {
        case t: Throwable ⇒ {
          _currentState = recoveryFailed // delay throwing exception to prepareRestart
          _recoveryFailureCause = t
          _recoveryFailureMessage = currentEnvelope
        }
      }
      case r: Recover ⇒ // ignore
      case _          ⇒ stashInternal()
    }
  }

  /**
   * Journals and processes new messages, both persistent and transient.
   */
  private val recoverySucceeded = new State {
    override def toString: String = "recovery finished"

    def aroundReceive(receive: Actor.Receive, message: Any) = message match {
      case r: Recover      ⇒ // ignore
      case Replayed(p)     ⇒ processPersistent(receive, p) // can occur after unstash from user stash
      case WriteSuccess(p) ⇒ processPersistent(receive, p)
      case WriteFailure(p, cause) ⇒ {
        val notification = PersistenceFailure(p.payload, p.sequenceNr, cause)
        if (receive.isDefinedAt(notification)) process(receive, notification)
        else {
          val errorMsg = "Processor killed after persistence failure " +
            s"(processor id = [${processorId}], sequence nr = [${p.sequenceNr}], payload class = [${p.payload.getClass.getName}]). " +
            "To avoid killing processors on persistence failure, a processor must handle PersistenceFailure messages."
          throw new ActorKilledException(errorMsg)
        }
      }
      case LoopSuccess(m)    ⇒ process(receive, m)
      case p: PersistentImpl ⇒ journal forward Write(p.copy(processorId = processorId, sequenceNr = nextSequenceNr()), self)
      case m                 ⇒ journal forward Loop(m, self)
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
      case ReplaySuccess(_) | ReplayFailure(_) ⇒ {
        _currentState = prepareRestart
        mailbox.enqueueFirst(self, _recoveryFailureMessage)
      }
      case Replayed(p) ⇒ updateLastSequenceNr(p)
      case r: Recover  ⇒ // ignore
      case _           ⇒ stashInternal()
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
   * Marks the `persistent` message as deleted. A message marked as deleted is not replayed during
   * recovery. This method is usually called inside `preRestartProcessor` when a persistent message
   * caused an exception. Processors that want to re-receive that persistent message during recovery
   * should not call this method.
   */
  def deleteMessage(persistent: Persistent): Unit = {
    journal ! Delete(persistent)
  }

  /**
   * Saves a `snapshot` of this processor's state. If saving succeeds, this processor will receive a
   * [[SaveSnapshotSuccess]] message, otherwise a [[SaveSnapshotFailure]] message.
   */
  def saveSnapshot(snapshot: Any): Unit = {
    snapshotStore ! SaveSnapshot(SnapshotMetadata(processorId, lastSequenceNr), snapshot)
  }

  /**
   * INTERNAL API.
   */
  final override protected[akka] def aroundReceive(receive: Actor.Receive, message: Any): Unit = {
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
      unstashAll(unstashFilterPredicate)
      unstashAllInternal()
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

  private def unstashFilterPredicate: Any ⇒ Boolean = {
    case _: WriteSuccess ⇒ false
    case _: Replayed     ⇒ false
    case _               ⇒ true
  }

  private var processorStash = Vector.empty[Envelope]

  private def stashInternal(): Unit = {
    processorStash :+= currentEnvelope
  }

  private def unstashAllInternal(): Unit = try {
    val i = processorStash.reverseIterator
    while (i.hasNext) mailbox.enqueueFirst(self, i.next())
  } finally {
    processorStash = Vector.empty[Envelope]
  }

  private def currentEnvelope: Envelope =
    context.asInstanceOf[ActorCell].currentMessage
}

/**
 * Java API.
 *
 * An actor that persists (journals) messages of type [[Persistent]]. Messages of other types are not persisted.
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
 */
abstract class UntypedProcessor extends UntypedActor with Processor {

  /**
   * Java API.
   *
   * Returns the current persistent message or `null` if there is none.
   */
  def getCurrentPersistentMessage = currentPersistentMessage.getOrElse(null)
}
