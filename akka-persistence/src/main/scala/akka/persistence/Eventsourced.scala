/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

import scala.collection.immutable
import scala.util.control.NonFatal
import akka.actor.{ StashOverflowException, DeadLetter, ActorCell }
import akka.annotation.InternalApi
import akka.dispatch.Envelope
import akka.util.Helpers.ConfigOps
import akka.event.Logging
import akka.event.LoggingAdapter
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
private[persistence] object Eventsourced {
  // ok to wrap around (2*Int.MaxValue restarts will not happen within a journal roundtrip)
  private val instanceIdCounter = new AtomicInteger(1)

  private sealed trait PendingHandlerInvocation {
    def evt: Any
    def handler: Any ⇒ Unit
  }
  /** forces actor to stash incoming commands until all these invocations are handled */
  private final case class StashingHandlerInvocation(evt: Any, handler: Any ⇒ Unit) extends PendingHandlerInvocation
  /** does not force the actor to stash commands; Originates from either `persistAsync` or `defer` calls */
  private final case class AsyncHandlerInvocation(evt: Any, handler: Any ⇒ Unit) extends PendingHandlerInvocation

  /** message used to detect that recovery timed out */
  private final case class RecoveryTick(snapshot: Boolean)
}

/**
 * INTERNAL API.
 *
 * Scala API and implementation details of [[PersistentActor]] and [[AbstractPersistentActor]].
 */
private[persistence] trait Eventsourced extends Snapshotter with PersistenceStash with PersistenceIdentity with PersistenceRecovery {
  import JournalProtocol._
  import SnapshotProtocol.LoadSnapshotResult
  import SnapshotProtocol.LoadSnapshotFailed
  import Eventsourced._

  private val extension = Persistence(context.system)

  private[persistence] lazy val journal = extension.journalFor(journalPluginId)
  private[persistence] lazy val snapshotStore = extension.snapshotStoreFor(snapshotPluginId)

  private val instanceId: Int = Eventsourced.instanceIdCounter.getAndIncrement()
  private val writerUuid = UUID.randomUUID.toString

  private var journalBatch = Vector.empty[PersistentEnvelope]
  // no longer used, but kept for binary compatibility
  private val maxMessageBatchSize = extension.journalConfigFor(journalPluginId).getInt("max-message-batch-size")
  private var writeInProgress = false
  private var sequenceNr: Long = 0L
  private var _lastSequenceNr: Long = 0L

  // safely null because we initialize it with a proper `waitingRecoveryPermit` state in aroundPreStart before any real action happens
  private var currentState: State = null

  // Used instead of iterating `pendingInvocations` in order to check if safe to revert to processing commands
  private var pendingStashingPersistInvocations: Long = 0
  // Holds user-supplied callbacks for persist/persistAsync calls
  private val pendingInvocations = new java.util.LinkedList[PendingHandlerInvocation]() // we only append / isEmpty / get(0) on it
  private var eventBatch: List[PersistentEnvelope] = Nil

  private val internalStash = createStash()

  private val unstashFilterPredicate: Any ⇒ Boolean = {
    case _: WriteMessageSuccess ⇒ false
    case _: ReplayedMessage     ⇒ false
    case _                      ⇒ true
  }

  /**
   * Returns `persistenceId`.
   */
  override def snapshotterId: String = persistenceId

  /**
   * Highest received sequence number so far or `0L` if this actor hasn't replayed
   * or stored any persistent events yet.
   */
  def lastSequenceNr: Long = _lastSequenceNr

  /**
   * Returns `lastSequenceNr`.
   */
  def snapshotSequenceNr: Long = lastSequenceNr

  /**
   * INTERNAL API.
   * Called whenever a message replay succeeds.
   * May be implemented by subclass.
   */
  private[akka] def onReplaySuccess(): Unit = ()

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * Subclass may override to customize logging.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param event the event that was processed in `receiveRecover`, if the exception
   *   was thrown there
   */
  protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit =
    event match {
      case Some(evt) ⇒
        log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}] for " +
          "persistenceId [{}].", evt.getClass.getName, lastSequenceNr, persistenceId)
      case None ⇒
        log.error(cause, "Persistence failure when replaying events for persistenceId [{}]. " +
          "Last known sequence number [{}]", persistenceId, lastSequenceNr)
    }

  /**
   * Called when persist fails. By default it logs the error.
   * Subclass may override to customize logging and for example send negative
   * acknowledgment to sender.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * Note that the event may or may not have been saved, depending on the type of
   * failure.
   *
   * @param cause failure cause.
   * @param event the event that was to be persisted
   */
  protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    log.error(cause, "Failed to persist event type [{}] with sequence number [{}] for persistenceId [{}].",
      event.getClass.getName, seqNr, persistenceId)
  }

  /**
   * Called when the journal rejected `persist` of an event. The event was not
   * stored. By default this method logs the problem as a warning, and the actor continues.
   * The callback handler that was passed to the `persist` method will not be invoked.
   *
   * @param cause failure cause
   * @param event the event that was to be persisted
   */
  protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    log.warning(
      "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
      event.getClass.getName, seqNr, persistenceId, cause.getMessage)
  }

  private def stashInternally(currMsg: Any): Unit =
    try internalStash.stash() catch {
      case e: StashOverflowException ⇒
        internalStashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val snd = sender()
            context.system.deadLetters.tell(DeadLetter(currMsg, snd, self), snd)
          case ReplyToStrategy(response) ⇒
            sender() ! response
          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }

  private def unstashInternally(all: Boolean): Unit =
    if (all) internalStash.unstashAll() else internalStash.unstash()

  private def startRecovery(recovery: Recovery): Unit = {
    changeState(recoveryStarted(recovery.replayMax))
    loadSnapshot(snapshotterId, recovery.fromSnapshot, recovery.toSequenceNr)
  }

  /** INTERNAL API. */
  override protected[akka] def aroundReceive(receive: Receive, message: Any): Unit =
    currentState.stateReceive(receive, message)

  /** INTERNAL API. */
  override protected[akka] def aroundPreStart(): Unit = {
    require(persistenceId ne null, s"persistenceId is [null] for PersistentActor [${self.path}]")

    // Fail fast on missing plugins.
    val j = journal; val s = snapshotStore
    requestRecoveryPermit()
    super.aroundPreStart()
  }

  private def requestRecoveryPermit(): Unit = {
    extension.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, self)
    changeState(waitingRecoveryPermit(recovery))
  }

  /** INTERNAL API. */
  override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    try {
      internalStash.unstashAll()
      unstashAll(unstashFilterPredicate)
    } finally {
      message match {
        case Some(WriteMessageSuccess(m, _)) ⇒
          flushJournalBatch()
          super.aroundPreRestart(reason, Some(m))
        case Some(LoopMessageSuccess(m, _)) ⇒
          flushJournalBatch()
          super.aroundPreRestart(reason, Some(m))
        case Some(ReplayedMessage(m)) ⇒
          flushJournalBatch()
          super.aroundPreRestart(reason, Some(m))
        case mo ⇒
          flushJournalBatch()
          super.aroundPreRestart(reason, mo)
      }
    }
  }

  /** INTERNAL API. */
  override protected[akka] def aroundPostRestart(reason: Throwable): Unit = {
    requestRecoveryPermit()
    super.aroundPostRestart(reason)
  }

  /** INTERNAL API. */
  override protected[akka] def aroundPostStop(): Unit =
    try {
      internalStash.unstashAll()
      unstashAll(unstashFilterPredicate)
    } finally super.aroundPostStop()

  override def unhandled(message: Any): Unit = {
    message match {
      case RecoveryCompleted ⇒ // mute
      case SaveSnapshotFailure(m, e) ⇒
        log.warning("Failed to saveSnapshot given metadata [{}] due to: [{}: {}]", m, e.getClass.getCanonicalName, e.getMessage)
      case DeleteSnapshotFailure(m, e) ⇒
        log.warning("Failed to deleteSnapshot given metadata [{}] due to: [{}: {}]", m, e.getClass.getCanonicalName, e.getMessage)
      case DeleteSnapshotsFailure(c, e) ⇒
        log.warning("Failed to deleteSnapshots given criteria [{}] due to: [{}: {}]", c, e.getClass.getCanonicalName, e.getMessage)
      case DeleteMessagesFailure(e, toSequenceNr) ⇒
        log.warning(
          "Failed to deleteMessages toSequenceNr [{}] for persistenceId [{}] due to [{}: {}].",
          toSequenceNr, persistenceId, e.getClass.getCanonicalName, e.getMessage)
      case m ⇒ super.unhandled(m)
    }
  }

  private def changeState(state: State): Unit = {
    currentState = state
  }

  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
    if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr

  private def setLastSequenceNr(value: Long): Unit =
    _lastSequenceNr = value

  private def nextSequenceNr(): Long = {
    sequenceNr += 1L
    sequenceNr
  }

  private def flushJournalBatch(): Unit =
    if (!writeInProgress && journalBatch.nonEmpty) {
      journal ! WriteMessages(journalBatch, self, instanceId)
      journalBatch = Vector.empty
      writeInProgress = true
    }

  private def log: LoggingAdapter = Logging(context.system, this)

  /**
   * Recovery handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing persistent actor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   * If there is a problem with recovering the state of the actor from the journal, the error
   * will be logged and the actor will be stopped.
   *
   * @see [[Recovery]]
   */
  def receiveRecover: Receive

  /**
   * Command handler. Typically validates commands against current state (and/or by
   * communication with other actors). On successful validation, one or more events are
   * derived from a command and these events are then persisted by calling `persist`.
   */
  def receiveCommand: Receive

  /**
   * Internal API
   */
  @InternalApi
  final private[akka] def internalPersist[A](event: A)(handler: A ⇒ Unit): Unit = {
    if (recoveryRunning) throw new IllegalStateException("Cannot persist during replay. Events can be persisted when receiving RecoveryCompleted or later.")
    pendingStashingPersistInvocations += 1
    pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
    eventBatch ::= AtomicWrite(PersistentRepr(event, persistenceId = persistenceId,
      sequenceNr = nextSequenceNr(), writerUuid = writerUuid, sender = sender()))
  }

  /**
   * Internal API
   */
  @InternalApi
  final private[akka] def internalPersistAll[A](events: immutable.Seq[A])(handler: A ⇒ Unit): Unit = {
    if (recoveryRunning) throw new IllegalStateException("Cannot persist during replay. Events can be persisted when receiving RecoveryCompleted or later.")
    if (events.nonEmpty) {
      events.foreach { event ⇒
        pendingStashingPersistInvocations += 1
        pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      }
      eventBatch ::= AtomicWrite(events.map(PersistentRepr.apply(_, persistenceId = persistenceId,
        sequenceNr = nextSequenceNr(), writerUuid = writerUuid, sender = sender())))
    }
  }

  /**
   * Internal API
   */
  @InternalApi
  final private[akka] def internalPersistAsync[A](event: A)(handler: A ⇒ Unit): Unit = {
    if (recoveryRunning) throw new IllegalStateException("Cannot persist during replay. Events can be persisted when receiving RecoveryCompleted or later.")
    pendingInvocations addLast AsyncHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
    eventBatch ::= AtomicWrite(PersistentRepr(event, persistenceId = persistenceId,
      sequenceNr = nextSequenceNr(), writerUuid = writerUuid, sender = sender()))
  }

  /**
   * Internal API
   */
  @InternalApi
  final private[akka] def internalPersistAllAsync[A](events: immutable.Seq[A])(handler: A ⇒ Unit): Unit = {
    if (recoveryRunning) throw new IllegalStateException("Cannot persist during replay. Events can be persisted when receiving RecoveryCompleted or later.")
    if (events.nonEmpty) {
      events.foreach { event ⇒
        pendingInvocations addLast AsyncHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      }
      eventBatch ::= AtomicWrite(events.map(PersistentRepr(_, persistenceId = persistenceId,
        sequenceNr = nextSequenceNr(), writerUuid = writerUuid, sender = sender())))
    }
  }

  /**
   * Internal API
   */
  @InternalApi
  final private[akka] def internalDeferAsync[A](event: A)(handler: A ⇒ Unit): Unit = {
    if (recoveryRunning) throw new IllegalStateException("Cannot defer during replay. Events can be deferred when receiving RecoveryCompleted or later.")
    if (pendingInvocations.isEmpty) {
      handler(event)
    } else {
      pendingInvocations addLast AsyncHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      eventBatch = NonPersistentRepr(event, sender()) :: eventBatch
    }
  }

  /**
   * Permanently deletes all persistent messages with sequence numbers less than or equal `toSequenceNr`.
   *
   * If the delete is successful a [[DeleteMessagesSuccess]] will be sent to the actor.
   * If the delete fails a [[DeleteMessagesFailure]] will be sent to the actor.
   *
   * @param toSequenceNr upper sequence number bound of persistent messages to be deleted.
   */
  def deleteMessages(toSequenceNr: Long): Unit =
    journal ! DeleteMessagesTo(persistenceId, toSequenceNr, self)

  /**
   * Returns `true` if this persistent actor is currently recovering.
   */
  def recoveryRunning: Boolean = {
    // currentState is null if this is called from constructor
    if (currentState == null) true else currentState.recoveryRunning
  }

  /**
   * Returns `true` if this persistent actor has successfully finished recovery.
   */
  def recoveryFinished: Boolean = !recoveryRunning

  override def stash(): Unit = {
    context.asInstanceOf[ActorCell].currentMessage match {
      case Envelope(_: JournalProtocol.Response, _) ⇒
        throw new IllegalStateException("Do not call stash inside of persist callback.")
      case _ ⇒ super.stash()
    }
  }

  override def unstashAll() {
    // Internally, all messages are processed by unstashing them from
    // the internal stash one-by-one. Hence, an unstashAll() from the
    // user stash must be prepended to the internal stash.
    internalStash.prepend(clearStash())
  }

  private trait State {
    def stateReceive(receive: Receive, message: Any): Unit
    def recoveryRunning: Boolean
  }

  /**
   * Initial state. Before starting the actual recovery it must get a permit from the
   * `RecoveryPermitter`. When starting many persistent actors at the same time
   * the journal and its data store is protected from being overloaded by limiting number
   * of recoveries that can be in progress at the same time. When receiving
   * `RecoveryPermitGranted` it switches to `recoveryStarted` state
   * All incoming messages are stashed.
   */
  private def waitingRecoveryPermit(recovery: Recovery) = new State {

    override def toString: String = s"waiting for recovery permit"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any) = message match {
      case RecoveryPermitter.RecoveryPermitGranted ⇒
        startRecovery(recovery)

      case other ⇒
        stashInternally(other)
    }
  }

  /**
   * Processes a loaded snapshot, if any. A loaded snapshot is offered with a `SnapshotOffer`
   * message to the actor's `receiveRecover`. Then initiates a message replay, either starting
   * from the loaded snapshot or from scratch, and switches to `recoveryStarted` state.
   * All incoming messages are stashed.
   *
   * @param replayMax maximum number of messages to replay.
   */
  private def recoveryStarted(replayMax: Long) = new State {

    // protect against snapshot stalling forever because of journal overloaded and such
    val timeout = extension.journalConfigFor(journalPluginId).getMillisDuration("recovery-event-timeout")
    val timeoutCancellable = {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(timeout, self, RecoveryTick(snapshot = true))
    }

    private val recoveryBehavior: Receive = {
      val _receiveRecover = receiveRecover

      {
        case PersistentRepr(payload, _) if recoveryRunning && _receiveRecover.isDefinedAt(payload) ⇒
          _receiveRecover(payload)
        case s: SnapshotOffer if _receiveRecover.isDefinedAt(s) ⇒
          _receiveRecover(s)
        case RecoveryCompleted if _receiveRecover.isDefinedAt(RecoveryCompleted) ⇒
          _receiveRecover(RecoveryCompleted)

      }
    }

    override def toString: String = s"recovery started (replayMax = [$replayMax])"
    override def recoveryRunning: Boolean = true

    override def stateReceive(receive: Receive, message: Any) = try message match {
      case LoadSnapshotResult(sso, toSnr) ⇒
        timeoutCancellable.cancel()
        sso.foreach {
          case SelectedSnapshot(metadata, snapshot) ⇒
            val offer = SnapshotOffer(metadata, snapshot)
            if (recoveryBehavior.isDefinedAt(offer)) {
              setLastSequenceNr(metadata.sequenceNr)
              // Since we are recovering we can ignore the receive behavior from the stack
              Eventsourced.super.aroundReceive(recoveryBehavior, offer)
            } else {
              unhandled(offer)
            }
        }
        changeState(recovering(recoveryBehavior, timeout))
        journal ! ReplayMessages(lastSequenceNr + 1L, toSnr, replayMax, persistenceId, self)

      case LoadSnapshotFailed(cause) ⇒
        timeoutCancellable.cancel()
        try onRecoveryFailure(cause, event = None) finally context.stop(self)

      case RecoveryTick(true) ⇒
        try onRecoveryFailure(
          new RecoveryTimedOut(s"Recovery timed out, didn't get snapshot within $timeout"),
          event = None)
        finally context.stop(self)

      case other ⇒
        stashInternally(other)
    } catch {
      case NonFatal(e) ⇒
        returnRecoveryPermit()
        throw e
    }

    private def returnRecoveryPermit(): Unit =
      extension.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, self)

  }

  /**
   * Processes replayed messages, if any. The actor's `receiveRecover` is invoked with the replayed
   * events.
   *
   * If replay succeeds it got highest stored sequence number response from the journal and then switches
   * to `processingCommands` state. Otherwise the actor is stopped.
   * If replay succeeds the `onReplaySuccess` callback method is called, otherwise `onRecoveryFailure`.
   *
   * All incoming messages are stashed.
   */
  private def recovering(recoveryBehavior: Receive, timeout: FiniteDuration) =
    new State {

      // protect against snapshot stalling forever because of journal overloaded and such
      val timeoutCancellable = {
        import context.dispatcher
        context.system.scheduler.schedule(timeout, timeout, self, RecoveryTick(snapshot = false))
      }
      var eventSeenInInterval = false
      var _recoveryRunning = true

      override def toString: String = "replay started"

      override def recoveryRunning: Boolean = _recoveryRunning

      override def stateReceive(receive: Receive, message: Any) = try message match {
        case ReplayedMessage(p) ⇒
          try {
            eventSeenInInterval = true
            updateLastSequenceNr(p)
            Eventsourced.super.aroundReceive(recoveryBehavior, p)
          } catch {
            case NonFatal(t) ⇒
              timeoutCancellable.cancel()
              try onRecoveryFailure(t, Some(p.payload)) finally context.stop(self)
              returnRecoveryPermit()
          }
        case RecoverySuccess(highestSeqNr) ⇒
          timeoutCancellable.cancel()
          onReplaySuccess() // callback for subclass implementation
          sequenceNr = highestSeqNr
          setLastSequenceNr(highestSeqNr)
          _recoveryRunning = false
          try Eventsourced.super.aroundReceive(recoveryBehavior, RecoveryCompleted)
          finally transitToProcessingState()
        case ReplayMessagesFailure(cause) ⇒
          timeoutCancellable.cancel()
          try onRecoveryFailure(cause, event = None) finally context.stop(self)
        case RecoveryTick(false) if !eventSeenInInterval ⇒
          timeoutCancellable.cancel()
          try onRecoveryFailure(
            new RecoveryTimedOut(s"Recovery timed out, didn't get event within $timeout, highest sequence number seen $lastSequenceNr"),
            event = None)
          finally context.stop(self)
        case RecoveryTick(false) ⇒
          eventSeenInInterval = false
        case RecoveryTick(true) ⇒
        // snapshot tick, ignore
        case other ⇒
          stashInternally(other)
      } catch {
        case NonFatal(e) ⇒
          returnRecoveryPermit()
          throw e
      }

      private def returnRecoveryPermit(): Unit =
        extension.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, self)

      private def transitToProcessingState(): Unit = {
        returnRecoveryPermit()

        if (eventBatch.nonEmpty) flushBatch()

        if (pendingStashingPersistInvocations > 0) changeState(persistingEvents)
        else {
          changeState(processingCommands)
          internalStash.unstashAll()
        }

      }
    }

  private def flushBatch() {
    if (eventBatch.nonEmpty) {
      journalBatch ++= eventBatch.reverse
      eventBatch = Nil
    }

    flushJournalBatch()
  }

  private def peekApplyHandler(payload: Any): Unit =
    try pendingInvocations.peek().handler(payload)
    finally flushBatch()

  /**
   * Common receive handler for processingCommands and persistingEvents
   */
  private abstract class ProcessingState extends State {
    override def recoveryRunning: Boolean = false

    val common: Receive = {
      case WriteMessageSuccess(p, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case we ignore the call to the handler
        if (id == instanceId) {
          updateLastSequenceNr(p)
          try {
            peekApplyHandler(p.payload)
            onWriteMessageComplete(err = false)
          } catch { case NonFatal(e) ⇒ onWriteMessageComplete(err = true); throw e }
        }
      case WriteMessageRejected(p, cause, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case the handler has already been discarded
        if (id == instanceId) {
          updateLastSequenceNr(p)
          onWriteMessageComplete(err = false)
          onPersistRejected(cause, p.payload, p.sequenceNr)
        }
      case WriteMessageFailure(p, cause, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case the handler has already been discarded
        if (id == instanceId) {
          onWriteMessageComplete(err = false)
          try onPersistFailure(cause, p.payload, p.sequenceNr) finally context.stop(self)
        }
      case LoopMessageSuccess(l, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case we ignore the call to the handler
        if (id == instanceId) {
          try {
            peekApplyHandler(l)
            onWriteMessageComplete(err = false)
          } catch { case NonFatal(e) ⇒ onWriteMessageComplete(err = true); throw e }
        }
      case WriteMessagesSuccessful ⇒
        writeInProgress = false
        flushJournalBatch()

      case WriteMessagesFailed(_) ⇒
        writeInProgress = false
        () // it will be stopped by the first WriteMessageFailure message

      case _: RecoveryTick ⇒
      // we may have one of these in the mailbox before the scheduled timeout
      // is cancelled when recovery has completed, just consume it so the concrete actor never sees it
    }

    def onWriteMessageComplete(err: Boolean): Unit
  }

  /**
   * Command processing state. If event persistence is pending after processing a
   * command, event persistence is triggered and state changes to `persistingEvents`.
   */
  private val processingCommands: State = new ProcessingState {
    override def toString: String = "processing commands"

    override def stateReceive(receive: Receive, message: Any) =
      if (common.isDefinedAt(message)) common(message)
      else try {
        Eventsourced.super.aroundReceive(receive, message)
        aroundReceiveComplete(err = false)
      } catch { case NonFatal(e) ⇒ aroundReceiveComplete(err = true); throw e }

    private def aroundReceiveComplete(err: Boolean): Unit = {
      if (eventBatch.nonEmpty) flushBatch()

      if (pendingStashingPersistInvocations > 0) changeState(persistingEvents)
      else unstashInternally(all = err)
    }

    override def onWriteMessageComplete(err: Boolean): Unit = {
      pendingInvocations.pop()
      unstashInternally(all = err)
    }
  }

  /**
   * Event persisting state. Remains until pending events are persisted and then changes
   * state to `processingCommands`. Only events to be persisted are processed. All other
   * messages are stashed internally.
   */
  private val persistingEvents: State = new ProcessingState {
    override def toString: String = "persisting events"

    override def stateReceive(receive: Receive, message: Any) =
      if (common.isDefinedAt(message)) common(message)
      else stashInternally(message)

    override def onWriteMessageComplete(err: Boolean): Unit = {
      pendingInvocations.pop() match {
        case _: StashingHandlerInvocation ⇒
          // enables an early return to `processingCommands`, because if this counter hits `0`,
          // we know the remaining pendingInvocations are all `persistAsync` created, which
          // means we can go back to processing commands also - and these callbacks will be called as soon as possible
          pendingStashingPersistInvocations -= 1
        case _ ⇒ // do nothing
      }

      if (pendingStashingPersistInvocations == 0) {
        changeState(processingCommands)
        unstashInternally(all = err)
      }
    }

  }

}
