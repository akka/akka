/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }

import scala.collection.immutable

import akka.japi.{ Procedure, Util }
import akka.persistence.JournalProtocol._

/**
 * INTERNAL API.
 *
 * Event sourcing mixin for a [[Processor]].
 */
private[persistence] trait Eventsourced extends Processor {
  private trait State {
    def aroundReceive(receive: Receive, message: Any): Unit
  }

  /**
   * Processor recovery state. Waits for recovery completion and then changes to
   * `processingCommands`
   */
  private val recovering: State = new State {
    def aroundReceive(receive: Receive, message: Any) {
      Eventsourced.super.aroundReceive(receive, message)
      message match {
        case _: ReplaySuccess | _: ReplayFailure ⇒ currentState = processingCommands
        case _                                   ⇒
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
    def aroundReceive(receive: Receive, message: Any) {
      Eventsourced.super.aroundReceive(receive, LoopSuccess(message))
      if (!persistInvocations.isEmpty) {
        currentState = persistingEvents
        Eventsourced.super.aroundReceive(receive, PersistentBatch(persistentEventBatch.reverse))
        persistInvocations = persistInvocations.reverse
        persistentEventBatch = Nil
      } else {
        processorStash.unstash()
      }
    }
  }

  /**
   * Event persisting state. Remains until pending events are persisted and then changes
   * state to `processingCommands`. Only events to be persisted are processed. All other
   * messages are stashed internally.
   */
  private val persistingEvents: State = new State {
    def aroundReceive(receive: Receive, message: Any) = message match {
      case PersistentBatch(b) ⇒ {
        b.foreach(p ⇒ deleteMessage(p, true))
        throw new UnsupportedOperationException("Persistent command batches not supported")
      }
      case p: PersistentRepr ⇒ {
        deleteMessage(p, true)
        throw new UnsupportedOperationException("Persistent commands not supported")
      }
      case WriteSuccess(p) if identical(p.payload, persistInvocations.head._1) ⇒ {
        withCurrentPersistent(p)(p ⇒ persistInvocations.head._2(p.payload))
        onWriteComplete()
      }
      case e @ WriteFailure(p, _) if identical(p.payload, persistInvocations.head._1) ⇒ {
        Eventsourced.super.aroundReceive(receive, message) // stops actor by default
        onWriteComplete()
      }
      case other ⇒ processorStash.stash()
    }

    def onWriteComplete(): Unit = {
      persistInvocations = persistInvocations.tail
      if (persistInvocations.isEmpty) {
        currentState = processingCommands
        processorStash.unstash()
      }
    }

    def identical(a: Any, b: Any): Boolean =
      a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]
  }

  private var persistInvocations: List[(Any, Any ⇒ Unit)] = Nil
  private var persistentEventBatch: List[PersistentRepr] = Nil

  private var currentState: State = recovering
  private val processorStash = createStash()

  /**
   * Asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event. It is guaranteed that no new commands will be received by a processor
   * between a call to `persist` and the execution of its `handler`. This also holds for
   * multiple `persist` calls per received command. Internally, this is achieved by stashing new
   * commands and unstashing them when the `event` has been persisted and handled. The stash used
   * for that is an internal stash which doesn't interfere with the user stash inherited from
   * [[Processor]].
   *
   * An event `handler` may close over processor state and modify it. The `sender` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * Within an event handler, applications usually update processor state using persisted event
   * data, notify listeners and reply to command senders.
   *
   * If persistence of an event fails, the processor will be stopped. This can be customized by
   * handling [[PersistenceFailure]] in [[receiveCommand]].
   *
   * @param event event to be persisted.
   * @param handler handler for each persisted `event`
   */
  final def persist[A](event: A)(handler: A ⇒ Unit): Unit = {
    persistInvocations = (event, handler.asInstanceOf[Any ⇒ Unit]) :: persistInvocations
    persistentEventBatch = PersistentRepr(event) :: persistentEventBatch
  }

  /**
   * Asynchronously persists `events` in specified order. This is equivalent to calling
   * `persist[A](event: A)(handler: A => Unit)` multiple times with the same `handler`,
   * except that `events` are persisted atomically with this method.
   *
   * @param events events to be persisted.
   * @param handler handler for each persisted `events`
   */
  final def persist[A](events: immutable.Seq[A])(handler: A ⇒ Unit): Unit =
    events.foreach(persist(_)(handler))

  /**
   * Replay handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing processor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   * @see [[Recover]]
   */
  def receiveReplay: Receive

  /**
   * Command handler. Typically validates commands against current state (and/or by
   * communication with other actors). On successful validation, one or more events are
   * derived from a command and these events are then persisted by calling `persist`.
   * Commands sent to event sourced processors should not be [[Persistent]] messages.
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
  final override protected[akka] def aroundReceive(receive: Receive, message: Any) {
    currentState.aroundReceive(receive, message)
  }

  /**
   * Calls `super.preRestart` then unstashes all messages from the internal stash.
   */
  override def preRestart(reason: Throwable, message: Option[Any]) {
    super.preRestart(reason, message)
    processorStash.unstashAll()
  }

  /**
   * Calls `super.postStop` then unstashes all messages from the internal stash.
   */
  override def postStop() {
    super.postStop()
    processorStash.unstashAll()
  }

  /**
   * INTERNAL API.
   */
  protected[persistence] val initialBehavior: Receive = {
    case Persistent(payload, _) if receiveReplay.isDefinedAt(payload) && recoveryRunning ⇒
      receiveReplay(payload)
    case s: SnapshotOffer if receiveReplay.isDefinedAt(s) ⇒
      receiveReplay(s)
    case f: RecoveryFailure if receiveReplay.isDefinedAt(f) ⇒
      receiveReplay(f)
    case msg if receiveCommand.isDefinedAt(msg) ⇒
      receiveCommand(msg)
  }
}

/**
 * An event sourced processor.
 */
trait EventsourcedProcessor extends Processor with Eventsourced {
  final def receive = initialBehavior
}

/**
 * Java API: an event sourced processor.
 */
abstract class UntypedEventsourcedProcessor extends UntypedProcessor with Eventsourced {
  final def onReceive(message: Any) = initialBehavior(message)

  final def receiveReplay: Receive = {
    case msg ⇒ onReceiveReplay(msg)
  }

  final def receiveCommand: Receive = {
    case msg ⇒ onReceiveCommand(msg)
  }

  /**
   * Java API: asynchronously persists `event`. On successful persistence, `handler` is called with the
   * persisted event. It is guaranteed that no new commands will be received by a processor
   * between a call to `persist` and the execution of its `handler`. This also holds for
   * multiple `persist` calls per received command. Internally, this is achieved by stashing new
   * commands and unstashing them when the `event` has been persisted and handled. The stash used
   * for that is an internal stash which doesn't interfere with the user stash inherited from
   * [[UntypedProcessor]].
   *
   * An event `handler` may close over processor state and modify it. The `getSender()` of a persisted
   * event is the sender of the corresponding command. This means that one can reply to a command
   * sender within an event `handler`.
   *
   * Within an event handler, applications usually update processor state using persisted event
   * data, notify listeners and reply to command senders.
   *
   * If persistence of an event fails, the processor will be stopped. This can be customized by
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
   * Java API: replay handler that receives persisted events during recovery. If a state snapshot
   * has been captured and saved, this handler will receive a [[SnapshotOffer]] message
   * followed by events that are younger than the offered snapshot.
   *
   * This handler must not have side-effects other than changing processor state i.e. it
   * should not perform actions that may fail, such as interacting with external services,
   * for example.
   *
   * @see [[Recover]]
   */
  def onReceiveReplay(msg: Any): Unit

  /**
   * Java API: command handler. Typically validates commands against current state (and/or by
   * communication with other actors). On successful validation, one or more events are
   * derived from a command and these events are then persisted by calling `persist`.
   * Commands sent to event sourced processors must not be [[Persistent]] or
   * [[PersistentBatch]] messages. In this case an `UnsupportedOperationException` is
   * thrown by the processor.
   */
  def onReceiveCommand(msg: Any): Unit
}
