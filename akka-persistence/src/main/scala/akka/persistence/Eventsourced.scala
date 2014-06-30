/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }

import akka.actor.{ AbstractActor, UntypedActor }
import akka.japi.{ Procedure, Util }
import akka.persistence.JournalProtocol._

import scala.collection.immutable

/**
 * INTERNAL API.
 *
 * Event sourcing mixin for a [[Processor]].
 */
private[persistence] trait Eventsourced extends ProcessorImpl {
  // TODO consolidate these traits as PersistentActor #15230

  /**
   * Processor recovery state. Waits for recovery completion and then changes to
   * `processingCommands`
   */
  private val recovering: State = new State {
    // cache the recoveryBehavior since it's a def for binary compatibility in 2.3.x
    private val _recoveryBehavior: Receive = recoveryBehavior

    override def toString: String = "recovering"

    def aroundReceive(receive: Receive, message: Any) {
      // Since we are recovering we can ignore the receive behavior from the stack
      Eventsourced.super.aroundReceive(_recoveryBehavior, message)
      message match {
        case _: ReadHighestSequenceNrSuccess | _: ReadHighestSequenceNrFailure ⇒
          currentState = processingCommands
        case _ ⇒
      }
    }
  }

  /**
   * Command processing state. If event persistence is pending after processing a
   * command, event persistence is triggered and state changes to `persistingEvents`.
   *
   * There's no need to loop commands though the journal any more i.e. they can now be
   * directly offered as `LoopSuccess` to the state machine implemented by `Processor`.
   */
  private val processingCommands: State = new State {
    override def toString: String = "processing commands"

    def aroundReceive(receive: Receive, message: Any) = message match {
      case _: ConfirmablePersistent ⇒
        doAroundReceive(receive, message)
      case PersistentBatch(b) ⇒
        throw new UnsupportedOperationException("Persistent command batches not supported")
      case _: PersistentRepr ⇒
        throw new UnsupportedOperationException("Persistent commands not supported")
      case WriteMessageSuccess(p, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case we ignore the call to the handler
        if (id == instanceId) {
          withCurrentPersistent(p)(p ⇒ pendingInvocations.peek().handler(p.payload))
          onWriteComplete()
        }
      case LoopMessageSuccess(l, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case we ignore the call to the handler
        if (id == instanceId) {
          pendingInvocations.peek().handler(l)
          onWriteComplete()
        }
      case s @ WriteMessagesSuccessful ⇒ Eventsourced.super.aroundReceive(receive, s)
      case f: WriteMessagesFailed      ⇒ Eventsourced.super.aroundReceive(receive, f)
      case _ ⇒
        doAroundReceive(receive, message)
    }

    private def doAroundReceive(receive: Receive, message: Any): Unit = {
      Eventsourced.super.aroundReceive(receive, LoopMessageSuccess(message, instanceId))

      if (pendingStashingPersistInvocations > 0) {
        currentState = persistingEvents
      }

      if (resequenceableEventBatch.nonEmpty) flushBatch()
      else processorStash.unstash()
    }

    private def onWriteComplete(): Unit = {
      pendingInvocations.pop()
    }
  }

  /**
   * Event persisting state. Remains until pending events are persisted and then changes
   * state to `processingCommands`. Only events to be persisted are processed. All other
   * messages are stashed internally.
   */
  private val persistingEvents: State = new State {
    override def toString: String = "persisting events"

    def aroundReceive(receive: Receive, message: Any): Unit = message match {
      case _: ConfirmablePersistent ⇒
        processorStash.stash()
      case PersistentBatch(b) ⇒
        b foreach {
          case p: PersistentRepr ⇒ deleteMessage(p.sequenceNr, permanent = true)
          case r                 ⇒ // ignore, nothing to delete (was not a persistent message)
        }
        throw new UnsupportedOperationException("Persistent command batches not supported")
      case p: PersistentRepr ⇒
        deleteMessage(p.sequenceNr, permanent = true)
        throw new UnsupportedOperationException("Persistent commands not supported")

      case WriteMessageSuccess(p, id) ⇒
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case we ignore the call to the handler
        if (id == instanceId) {
          withCurrentPersistent(p)(p ⇒ pendingInvocations.peek().handler(p.payload))
          onWriteComplete()
        }

      case e @ WriteMessageFailure(p, _, id) ⇒
        Eventsourced.super.aroundReceive(receive, message) // stops actor by default
        // instanceId mismatch can happen for persistAsync and defer in case of actor restart
        // while message is in flight, in that case the handler has already been discarded
        if (id == instanceId)
          onWriteComplete()
      case LoopMessageSuccess(l, id) ⇒
        if (id == instanceId) {
          pendingInvocations.peek().handler(l)
          onWriteComplete()
        }
      case s @ WriteMessagesSuccessful ⇒ Eventsourced.super.aroundReceive(receive, s)
      case f: WriteMessagesFailed      ⇒ Eventsourced.super.aroundReceive(receive, f)
      case other                       ⇒ processorStash.stash()
    }

    private def onWriteComplete(): Unit = {
      pendingInvocations.pop() match {
        case _: StashingHandlerInvocation ⇒
          // enables an early return to `processingCommands`, because if this counter hits `0`,
          // we know the remaining pendingInvocations are all `persistAsync` created, which
          // means we can go back to processing commands also - and these callbacks will be called as soon as possible
          pendingStashingPersistInvocations -= 1
        case _ ⇒ // do nothing
      }

      if (pendingStashingPersistInvocations == 0) {
        currentState = processingCommands
        processorStash.unstash()
      }
    }

  }

  /**
   * INTERNAL API.
   *
   * This is a def and not a val because of binary compatibility in 2.3.x.
   * It is cached where it is used.
   */
  private def recoveryBehavior: Receive = {
    case Persistent(payload, _) if recoveryRunning && receiveRecover.isDefinedAt(payload) ⇒
      receiveRecover(payload)
    case s: SnapshotOffer if receiveRecover.isDefinedAt(s) ⇒
      receiveRecover(s)
    case f: RecoveryFailure if receiveRecover.isDefinedAt(f) ⇒
      receiveRecover(f)
    case RecoveryCompleted if receiveRecover.isDefinedAt(RecoveryCompleted) ⇒
      receiveRecover(RecoveryCompleted)
  }

  private sealed trait PendingHandlerInvocation {
    def evt: Any
    def handler: Any ⇒ Unit
  }
  /** forces processor to stash incoming commands untill all these invocations are handled */
  private final case class StashingHandlerInvocation(evt: Any, handler: Any ⇒ Unit) extends PendingHandlerInvocation
  /** does not force the processor to stash commands; Originates from either `persistAsync` or `defer` calls */
  private final case class AsyncHandlerInvocation(evt: Any, handler: Any ⇒ Unit) extends PendingHandlerInvocation

  /** Used instead of iterating `pendingInvocations` in order to check if safe to revert to processing commands */
  private var pendingStashingPersistInvocations: Long = 0
  /** Holds user-supplied callbacks for persist/persistAsync calls */
  private val pendingInvocations = new java.util.LinkedList[PendingHandlerInvocation]() // we only append / isEmpty / get(0) on it
  private var resequenceableEventBatch: List[Resequenceable] = Nil
  // When using only `persistAsync` and `defer` max throughput is increased by using the
  // batching implemented in `Processor`, but when using `persist` we want to use the atomic
  // PeristentBatch for the emitted events. This implementation can be improved when
  // Processor and Eventsourced are consolidated into one class 
  private var useProcessorBatching: Boolean = true

  private var currentState: State = recovering
  private val processorStash = createStash()

  private def flushBatch() {
    if (useProcessorBatching)
      resequenceableEventBatch.reverse foreach { Eventsourced.super.aroundReceive(receive, _) }
    else
      Eventsourced.super.aroundReceive(receive, PersistentBatch(resequenceableEventBatch.reverse))

    resequenceableEventBatch = Nil
    useProcessorBatching = true
  }

  /**
   * Asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event. It is guaranteed that no new commands will be received by a persistent actor
   * between a call to `persist` and the execution of its `handler`. This also holds for
   * multiple `persist` calls per received command. Internally, this is achieved by stashing new
   * commands and unstashing them when the `event` has been persisted and handled. The stash used
   * for that is an internal stash which doesn't interfere with the inherited user stash.
   *
   * An event `handler` may close over persistent actor state and modify it. The `sender` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * Within an event handler, applications usually update persistent actor state using persisted event
   * data, notify listeners and reply to command senders.
   *
   * If persistence of an event fails, the persistent actor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[receiveCommand]].
   *
   * @param event event to be persisted
   * @param handler handler for each persisted `event`
   */
  final def persist[A](event: A)(handler: A ⇒ Unit): Unit = {
    pendingStashingPersistInvocations += 1
    pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
    resequenceableEventBatch = PersistentRepr(event) :: resequenceableEventBatch
    useProcessorBatching = false
  }

  /**
   * Asynchronously persists `events` in specified order. This is equivalent to calling
   * `persist[A](event: A)(handler: A => Unit)` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted
   * @param handler handler for each persisted `events`
   */
  final def persist[A](events: immutable.Seq[A])(handler: A ⇒ Unit): Unit =
    events.foreach(persist(_)(handler))

  /**
   * Asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event.
   *
   * Unlike `persist` the persistent actor will continue to receive incomming commands between the
   * call to `persist` and executing it's `handler`. This asynchronous, non-stashing, version of
   * of persist should be used when you favor throughput over the "command-2 only processed after
   * command-1 effects' have been applied" guarantee, which is provided by the plain [[persist]] method.
   *
   * An event `handler` may close over persistent actor state and modify it. The `sender` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * If persistence of an event fails, the persistent actor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[receiveCommand]].
   *
   * @param event event to be persisted
   * @param handler handler for each persisted `event`
   */
  final def persistAsync[A](event: A)(handler: A ⇒ Unit): Unit = {
    pendingInvocations addLast AsyncHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
    resequenceableEventBatch = PersistentRepr(event) :: resequenceableEventBatch
  }

  /**
   * Asynchronously persists `events` in specified order. This is equivalent to calling
   * `persistAsync[A](event: A)(handler: A => Unit)` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted
   * @param handler handler for each persisted `events`
   */
  final def persistAsync[A](events: immutable.Seq[A])(handler: A ⇒ Unit): Unit =
    events.foreach(persistAsync(_)(handler))

  /**
   * Defer the handler execution until all pending handlers have been executed.
   * Allows to define logic within the actor, which will respect the invocation-order-guarantee
   * in respect to `persistAsync` calls. That is, if `persistAsync` was invoked before defer,
   * the corresponding handlers will be invoked in the same order as they were registered in.
   *
   * This call will NOT result in `event` being persisted, please use `persist` or `persistAsync`,
   * if the given event should possible to replay.
   *
   * If there are no pending persist handler calls, the handler will be called immediatly.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param event event to be handled in the future, when preceeding persist operations have been processes
   * @param handler handler for the given `event`
   */
  final def defer[A](event: A)(handler: A ⇒ Unit): Unit = {
    if (pendingInvocations.isEmpty) {
      handler(event)
    } else {
      pendingInvocations addLast AsyncHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      resequenceableEventBatch = NonPersistentRepr(event, sender()) :: resequenceableEventBatch
    }
  }

  /**
   * Defer the handler execution until all pending handlers have been executed.
   * Allows to define logic within the actor, which will respect the invocation-order-guarantee
   * in respect to `persistAsync` calls. That is, if `persistAsync` was invoked before defer,
   * the corresponding handlers will be invoked in the same order as they were registered in.
   *
   * This call will NOT result in `event` being persisted, please use `persist` or `persistAsync`,
   * if the given event should possible to replay.
   *
   * If there are no pending persist handler calls, the handler will be called immediatly.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param events event to be handled in the future, when preceeding persist operations have been processes
   * @param handler handler for each `event`
   */
  final def defer[A](events: immutable.Seq[A])(handler: A ⇒ Unit): Unit =
    events.foreach(defer(_)(handler))

  /**
   * Recovery handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing persistent actor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   * If recovery fails, the actor will be stopped. This can be customized by
   * handling [[RecoveryFailure]].
   *
   * @see [[Recover]]
   */
  def receiveRecover: Receive

  /**
   * Command handler. Typically validates commands against current state (and/or by
   * communication with other actors). On successful validation, one or more events are
   * derived from a command and these events are then persisted by calling `persist`.
   * Commands sent to event sourced persistent actors should not be [[Persistent]] messages.
   */
  def receiveCommand: Receive

  override def unstashAll() {
    // Internally, all messages are processed by unstashing them from
    // the internal stash one-by-one. Hence, an unstashAll() from the
    // user stash must be prepended to the internal stash.
    processorStash.prepend(clearStash())
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundReceive(receive: Receive, message: Any) {
    currentState.aroundReceive(receive, message)
  }

  /**
   * INTERNAL API.
   */
  override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // flushJournalBatch will send outstanding persistAsync and defer events to the journal
    // and also prevent those to be unstashed in Processor.aroundPreRestart
    flushJournalBatch()
    super.aroundPreRestart(reason, message)
  }

  /**
   * Calls `super.preRestart` then unstashes all messages from the internal stash.
   */
  override def preRestart(reason: Throwable, message: Option[Any]) {
    processorStash.unstashAll()
    super.preRestart(reason, message)
  }

  /**
   * Calls `super.postStop` then unstashes all messages from the internal stash.
   */
  override def postStop() {
    processorStash.unstashAll()
    super.postStop()
  }

  /**
   * INTERNAL API.
   *
   * Only here for binary compatibility in 2.3.x.
   */
  protected[persistence] val initialBehavior: Receive = recoveryBehavior orElse {
    case msg if receiveCommand.isDefinedAt(msg) ⇒
      receiveCommand(msg)
  }
}

/**
 * An event sourced processor.
 */
@deprecated("EventsourcedProcessor will be removed in 2.4.x, instead extend the API equivalent `akka.persistence.PersistentProcessor`", since = "2.3.4")
trait EventsourcedProcessor extends Processor with Eventsourced {
  // TODO remove Processor #15230
  def receive = receiveCommand
}

/**
 * An persistent Actor - can be used to implement command or event sourcing.
 */
trait PersistentActor extends ProcessorImpl with Eventsourced {
  def receive = receiveCommand
}

/**
 * Java API: an persistent actor - can be used to implement command or event sourcing.
 */
abstract class UntypedPersistentActor extends UntypedActor with ProcessorImpl with Eventsourced {

  final def onReceive(message: Any) = onReceiveCommand(message)

  final def receiveRecover: Receive = {
    case msg ⇒ onReceiveRecover(msg)
  }

  final def receiveCommand: Receive = {
    case msg ⇒ onReceiveCommand(msg)
  }

  /**
   * Java API: asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event. It is guaranteed that no new commands will be received by a persistent actor
   * between a call to `persist` and the execution of its `handler`. This also holds for
   * multiple `persist` calls per received command. Internally, this is achieved by stashing new
   * commands and unstashing them when the `event` has been persisted and handled. The stash used
   * for that is an internal stash which doesn't interfere with the inherited user stash.
   *
   * An event `handler` may close over persistent actor state and modify it. The `getSender()` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * Within an event handler, applications usually update persistent actor state using persisted event
   * data, notify listeners and reply to command senders.
   *
   * If persistence of an event fails, the persistent actor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[onReceiveCommand]].
   *
   * @param event event to be persisted.
   * @param handler handler for each persisted `event`
   */
  final def persist[A](event: A, handler: Procedure[A]): Unit =
    persist(event)(event ⇒ handler(event))

  /**
   * Java API: asynchronously persists `events` in specified order. This is equivalent to calling
   * `persist[A](event: A, handler: Procedure[A])` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted.
   * @param handler handler for each persisted `events`
   */
  final def persist[A](events: JIterable[A], handler: Procedure[A]): Unit =
    persist(Util.immutableSeq(events))(event ⇒ handler(event))

  /**
   * JAVA API: asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event.
   *
   * Unlike `persist` the persistent actor will continue to receive incomming commands between the
   * call to `persist` and executing it's `handler`. This asynchronous, non-stashing, version of
   * of persist should be used when you favor throughput over the "command-2 only processed after
   * command-1 effects' have been applied" guarantee, which is provided by the plain [[persist]] method.
   *
   * An event `handler` may close over persistent actor state and modify it. The `sender` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * If persistence of an event fails, the persistent actor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[receiveCommand]].
   *
   * @param event event to be persisted
   * @param handler handler for each persisted `event`
   */
  final def persistAsync[A](event: A)(handler: Procedure[A]): Unit =
    super[Eventsourced].persistAsync(event)(event ⇒ handler(event))

  /**
   * JAVA API: asynchronously persists `events` in specified order. This is equivalent to calling
   * `persistAsync[A](event: A)(handler: A => Unit)` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted
   * @param handler handler for each persisted `events`
   */
  final def persistAsync[A](events: JIterable[A])(handler: A ⇒ Unit): Unit =
    super[Eventsourced].persistAsync(Util.immutableSeq(events))(event ⇒ handler(event))

  /**
   * Defer the handler execution until all pending handlers have been executed.
   * Allows to define logic within the actor, which will respect the invocation-order-guarantee
   * in respect to `persistAsync` calls. That is, if `persistAsync` was invoked before defer,
   * the corresponding handlers will be invoked in the same order as they were registered in.
   *
   * This call will NOT result in `event` being persisted, please use `persist` or `persistAsync`,
   * if the given event should possible to replay.
   *
   * If there are no pending persist handler calls, the handler will be called immediatly.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param event event to be handled in the future, when preceeding persist operations have been processes
   * @param handler handler for the given `event`
   */
  final def defer[A](event: A)(handler: Procedure[A]): Unit =
    super[Eventsourced].defer(event)(event ⇒ handler(event))

  /**
   * Defer the handler execution until all pending handlers have been executed.
   * Allows to define logic within the actor, which will respect the invocation-order-guarantee
   * in respect to `persistAsync` calls. That is, if `persistAsync` was invoked before defer,
   * the corresponding handlers will be invoked in the same order as they were registered in.
   *
   * This call will NOT result in `event` being persisted, please use `persist` or `persistAsync`,
   * if the given event should possible to replay.
   *
   * If there are no pending persist handler calls, the handler will be called immediatly.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param events event to be handled in the future, when preceeding persist operations have been processes
   * @param handler handler for each `event`
   */
  final def defer[A](events: JIterable[A])(handler: Procedure[A]): Unit =
    super[Eventsourced].defer(Util.immutableSeq(events))(event ⇒ handler(event))

  /**
   * Java API: recovery handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing persistent actor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   * If recovery fails, the actor will be stopped. This can be customized by
   * handling [[RecoveryFailure]].
   *
   * @see [[Recover]]
   */
  @throws(classOf[Exception])
  def onReceiveRecover(msg: Any): Unit

  /**
   * Java API: command handler. Typically validates commands against current state (and/or by
   * communication with other actors). On successful validation, one or more events are
   * derived from a command and these events are then persisted by calling `persist`.
   * Commands sent to event sourced persistent actors must not be [[Persistent]] or
   * [[PersistentBatch]] messages. In this case an `UnsupportedOperationException` is
   * thrown by the persistent actor.
   */
  @throws(classOf[Exception])
  def onReceiveCommand(msg: Any): Unit
}

/**
 * Java API: an persistent actor - can be used to implement command or event sourcing.
 */
abstract class AbstractPersistentActor extends AbstractActor with PersistentActor with Eventsourced {

  /**
   * Java API: asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event. It is guaranteed that no new commands will be received by a persistent actor
   * between a call to `persist` and the execution of its `handler`. This also holds for
   * multiple `persist` calls per received command. Internally, this is achieved by stashing new
   * commands and unstashing them when the `event` has been persisted and handled. The stash used
   * for that is an internal stash which doesn't interfere with the inherited user stash.
   *
   * An event `handler` may close over persistent actor state and modify it. The `getSender()` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * Within an event handler, applications usually update persistent actor state using persisted event
   * data, notify listeners and reply to command senders.
   *
   * If persistence of an event fails, the persistent actor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[receiveCommand]].
   *
   * @param event event to be persisted.
   * @param handler handler for each persisted `event`
   */
  final def persist[A](event: A, handler: Procedure[A]): Unit =
    persist(event)(event ⇒ handler(event))

  /**
   * Java API: asynchronously persists `events` in specified order. This is equivalent to calling
   * `persist[A](event: A, handler: Procedure[A])` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted.
   * @param handler handler for each persisted `events`
   */
  final def persist[A](events: JIterable[A], handler: Procedure[A]): Unit =
    persist(Util.immutableSeq(events))(event ⇒ handler(event))

  /**
   * Java API: asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event.
   *
   * Unlike `persist` the persistent actor will continue to receive incomming commands between the
   * call to `persistAsync` and executing it's `handler`. This asynchronous, non-stashing, version of
   * of persist should be used when you favor throughput over the strict ordering guarantees that `persist` guarantees.
   *
   * If persistence of an event fails, the persistent actor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[receiveCommand]].
   *
   * @param event event to be persisted
   * @param handler handler for each persisted `event`
   */
  final def persistAsync[A](event: A, handler: Procedure[A]): Unit =
    persistAsync(event)(event ⇒ handler(event))

  /**
   * Defer the handler execution until all pending handlers have been executed.
   * Allows to define logic within the actor, which will respect the invocation-order-guarantee
   * in respect to `persistAsync` calls. That is, if `persistAsync` was invoked before defer,
   * the corresponding handlers will be invoked in the same order as they were registered in.
   *
   * This call will NOT result in `event` being persisted, please use `persist` or `persistAsync`,
   * if the given event should possible to replay.
   *
   * If there are no pending persist handler calls, the handler will be called immediatly.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param event event to be handled in the future, when preceeding persist operations have been processes
   * @param handler handler for the given `event`
   */
  final def defer[A](event: A)(handler: Procedure[A]): Unit =
    super.defer(event)(event ⇒ handler(event))

  /**
   * Defer the handler execution until all pending handlers have been executed.
   * Allows to define logic within the actor, which will respect the invocation-order-guarantee
   * in respect to `persistAsync` calls. That is, if `persistAsync` was invoked before defer,
   * the corresponding handlers will be invoked in the same order as they were registered in.
   *
   * This call will NOT result in `event` being persisted, please use `persist` or `persistAsync`,
   * if the given event should possible to replay.
   *
   * If there are no pending persist handler calls, the handler will be called immediatly.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param events event to be handled in the future, when preceeding persist operations have been processes
   * @param handler handler for each `event`
   */
  final def defer[A](events: JIterable[A])(handler: Procedure[A]): Unit =
    super.defer(Util.immutableSeq(events))(event ⇒ handler(event))

  /**
   * Java API: asynchronously persists `events` in specified order. This is equivalent to calling
   * `persistAsync[A](event: A)(handler: A => Unit)` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted
   * @param handler handler for each persisted `events`
   */
  final def persistAsync[A](events: JIterable[A], handler: Procedure[A]): Unit =
    persistAsync(Util.immutableSeq(events))(event ⇒ handler(event))

  override def receive = super[PersistentActor].receive

}

/**
 * Java API: an event sourced processor.
 */
@deprecated("UntypedEventsourcedProcessor will be removed in 2.4.x, instead extend the API equivalent `akka.persistence.PersistentProcessor`", since = "2.3.4")
abstract class UntypedEventsourcedProcessor extends UntypedPersistentActor {
  override def persistenceId: String = processorId
}

/**
 * Java API: compatible with lambda expressions (to be used with [[akka.japi.pf.ReceiveBuilder]]):
 * command handler. Typically validates commands against current state (and/or by
 * communication with other actors). On successful validation, one or more events are
 * derived from a command and these events are then persisted by calling `persist`.
 * Commands sent to event sourced processors must not be [[Persistent]] or
 * [[PersistentBatch]] messages. In this case an `UnsupportedOperationException` is
 * thrown by the processor.
 */
@deprecated("AbstractEventsourcedProcessor will be removed in 2.4.x, instead extend the API equivalent `akka.persistence.PersistentProcessor`", since = "2.3.4")
abstract class AbstractEventsourcedProcessor extends AbstractPersistentActor {
  override def persistenceId: String = processorId
}
