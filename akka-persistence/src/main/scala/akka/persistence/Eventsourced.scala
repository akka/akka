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
   * Command processing state. If event persistence is pending after processing a
   * command, event persistence is triggered and state changes to `persistingEvents`.
   */
  private val processingCommands: State = new State {
    def aroundReceive(receive: Receive, message: Any) = message match {
      case m if (persistInvocations.isEmpty) ⇒ {
        Eventsourced.super.aroundReceive(receive, m)
        if (!persistInvocations.isEmpty) {
          persistInvocations = persistInvocations.reverse
          persistCandidates = persistCandidates.reverse
          persistCandidates.foreach(self forward Persistent(_))
          currentState = persistingEvents
        }
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
      case p: PersistentImpl if identical(p.payload, persistCandidates.head) ⇒ {
        Eventsourced.super.aroundReceive(receive, message)
        persistCandidates = persistCandidates.tail
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
        processorStash.unstashAll()
      }
    }

    def identical(a: Any, b: Any): Boolean =
      a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]
  }

  private var persistInvocations: List[(Any, Any ⇒ Unit)] = Nil
  private var persistCandidates: List[Any] = Nil

  private var currentState: State = processingCommands
  private val processorStash = createProcessorStash

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
    persistCandidates = event :: persistCandidates
  }

  /**
   * Asynchronously persists `events` in specified order. This is equivalent to calling
   * `persist[A](event: A)(handler: A => Unit)` multiple times with the same `handler`.
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

  /**
   * INTERNAL API.
   */
  final override protected[akka] def aroundReceive(receive: Receive, message: Any) {
    currentState.aroundReceive(receive, message)
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
 * Java API.
 *
 * An event sourced processor.
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
   * Java API.
   *
   * Asynchronously persists `event`. On successful persistence, `handler` is called with the
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
   * Java API.
   *
   * Asynchronously persists `events` in specified order. This is equivalent to calling
   * `persist[A](event: A, handler: Procedure[A])` multiple times with the same `handler`.
   *
   * @param events events to be persisted.
   * @param handler handler for each persisted `events`
   */
  final def persist[A](events: JIterable[A], handler: Procedure[A]): Unit =
    persist(Util.immutableSeq(events))(event ⇒ handler(event))

  /**
   * Java API.
   *
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
  def onReceiveReplay(msg: Any): Unit

  /**
   * Java API.
   *
   * Command handler. Typically validates commands against current state (and/or by
   * communication with other actors). On successful validation, one or more events are
   * derived from a command and these events are then persisted by calling `persist`.
   * Commands sent to event sourced processors should not be [[Persistent]] messages.
   */
  def onReceiveCommand(msg: Any): Unit
}