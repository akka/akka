/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }
import akka.actor.UntypedActor
import akka.japi.Procedure
import akka.actor.AbstractActor
import akka.japi.Util

/**
 * Sent to a [[PersistentActor]] if a journal fails to write a persistent message. If
 * not handled, an `akka.actor.ActorKilledException` is thrown by that persistent actor.
 *
 * @param payload payload of the persistent message.
 * @param sequenceNr sequence number of the persistent message.
 * @param cause failure cause.
 */
@SerialVersionUID(1L)
case class PersistenceFailure(payload: Any, sequenceNr: Long, cause: Throwable)

/**
 * Sent to a [[PersistentActor]] if a journal fails to replay messages or fetch that persistent actor's
 * highest sequence number. If not handled, the actor will be stopped.
 *
 * Contains the [[#sequenceNr]] of the message that could not be replayed, if it
 * failed at a specific message.
 *
 * Contains the [[#payload]] of the message that could not be replayed, if it
 * failed at a specific message.
 */
@SerialVersionUID(1L)
case class RecoveryFailure(cause: Throwable)(failingMessage: Option[(Long, Any)]) {
  override def toString: String = failingMessage match {
    case Some((sequenceNr, payload)) ⇒ s"RecoveryFailure(${cause.getMessage},$sequenceNr,$payload)"
    case None                        ⇒ s"RecoveryFailure(${cause.getMessage})"
  }

  def sequenceNr: Option[Long] = failingMessage.map { case (snr, _) ⇒ snr }

  def payload: Option[Any] = failingMessage.map { case (_, payload) ⇒ payload }
}

abstract class RecoveryCompleted
/**
 * Sent to a [[PersistentActor]] when the journal replay has been finished.
 */
@SerialVersionUID(1L)
case object RecoveryCompleted extends RecoveryCompleted {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * Instructs a persistent actor to recover itself. Recovery will start from a snapshot if the persistent actor has
 * previously saved one or more snapshots and at least one of these snapshots matches the specified
 * `fromSnapshot` criteria. Otherwise, recovery will start from scratch by replaying all journaled
 * messages.
 *
 * If recovery starts from a snapshot, the persistent actor is offered that snapshot with a [[SnapshotOffer]]
 * message, followed by replayed messages, if any, that are younger than the snapshot, up to the
 * specified upper sequence number bound (`toSequenceNr`).
 *
 * @param fromSnapshot criteria for selecting a saved snapshot from which recovery should start. Default
 *                     is latest (= youngest) snapshot.
 * @param toSequenceNr upper sequence number bound (inclusive) for recovery. Default is no upper bound.
 * @param replayMax maximum number of messages to replay. Default is no limit.
 */
@SerialVersionUID(1L)
final case class Recover(fromSnapshot: SnapshotSelectionCriteria = SnapshotSelectionCriteria.Latest, toSequenceNr: Long = Long.MaxValue, replayMax: Long = Long.MaxValue)

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

  /**
   * Java API.
   *
   * @see [[Recover]]
   */
  def create(fromSnapshot: SnapshotSelectionCriteria, toSequenceNr: Long, replayMax: Long) =
    Recover(fromSnapshot, toSequenceNr, replayMax)
}

/**
 * An persistent Actor - can be used to implement command or event sourcing.
 */
trait PersistentActor extends Eventsourced {
  def receive = receiveCommand
}

/**
 * Java API: an persistent actor - can be used to implement command or event sourcing.
 */
abstract class UntypedPersistentActor extends UntypedActor with Eventsourced {

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
   * Unlike `persist` the persistent actor will continue to receive incoming commands between the
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
   * If there are no pending persist handler calls, the handler will be called immediately.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param event event to be handled in the future, when preceding persist operations have been processes
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
   * If there are no pending persist handler calls, the handler will be called immediately.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param events event to be handled in the future, when preceding persist operations have been processes
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
   * Unlike `persist` the persistent actor will continue to receive incoming commands between the
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
   * If there are no pending persist handler calls, the handler will be called immediately.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param event event to be handled in the future, when preceding persist operations have been processes
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
   * If there are no pending persist handler calls, the handler will be called immediately.
   *
   * In the event of persistence failures (indicated by [[PersistenceFailure]] messages being sent to the
   * [[PersistentActor]], you can handle these messages, which in turn will enable the deferred handlers to run afterwards.
   * If persistence failure messages are left `unhandled`, the default behavior is to stop the Actor, thus the handlers
   * will not be run.
   *
   * @param events event to be handled in the future, when preceding persist operations have been processes
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

