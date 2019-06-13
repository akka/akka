/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.Collections
import java.util.Optional

import akka.actor.typed
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.actor.typed.internal.BehaviorImpl.DeferredBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.InternalApi
import akka.persistence.typed.EventAdapter
import akka.persistence.typed._
import akka.persistence.typed.internal._
import akka.util.unused

abstract class EventSourcedBehavior[Command, Event, State] private[akka] (
    val persistenceId: PersistenceId,
    onPersistFailure: Optional[BackoffSupervisorStrategy])
    extends DeferredBehavior[Command] {

  def this(persistenceId: PersistenceId) = {
    this(persistenceId, Optional.empty[BackoffSupervisorStrategy])
  }

  def this(persistenceId: PersistenceId, onPersistFailure: BackoffSupervisorStrategy) = {
    this(persistenceId, Optional.ofNullable(onPersistFailure))
  }

  /**
   * Factory of effects.
   *
   * Return effects from your handlers in order to instruct persistence on how to act on the incoming message (i.e. persist events).
   */
  protected final def Effect: EffectFactories[Event, State] =
    EffectFactories.asInstanceOf[EffectFactories[Event, State]]

  /**
   * Implement by returning the initial empty state object.
   * This object will be passed into this behaviors handlers, until a new state replaces it.
   *
   * Also known as "zero state" or "neutral state".
   */
  protected def emptyState: State

  /**
   * Implement by handling incoming commands and return an `Effect()` to persist or signal other effects
   * of the command handling such as stopping the behavior or others.
   *
   * Use [[EventSourcedBehavior#newCommandHandlerBuilder]] to define the command handlers.
   *
   * The command handlers are only invoked when the actor is running (i.e. not replaying).
   * While the actor is persisting events, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  protected def commandHandler(): CommandHandler[Command, Event, State]

  /**
   * Implement by applying the event to the current state in order to return a new state.
   *
   * Use [[EventSourcedBehavior#newEventHandlerBuilder]] to define the event handlers.
   *
   * The event handlers are invoked during recovery as well as running operation of this behavior,
   * in order to keep updating the state state.
   *
   * For that reason it is strongly discouraged to perform side-effects in this handler;
   * Side effects should be executed in `andThen` or `recoveryCompleted` blocks.
   */
  protected def eventHandler(): EventHandler[State, Event]

  /**
   * Override to react on general lifecycle signals and persistence specific signals (subtypes of
   * [[akka.persistence.typed.EventSourcedSignal]]).
   *
   * Use [[EventSourcedBehavior#newSignalHandlerBuilder]] to define the signal handler.
   */
  protected def signalHandler(): SignalHandler[State] = SignalHandler.empty[State]

  /**
   * @return A new, mutable signal handler builder
   */
  protected final def newSignalHandlerBuilder(): SignalHandlerBuilder[State] =
    SignalHandlerBuilder.builder[State]

  /**
   * @return A new, mutable, command handler builder
   */
  protected def newCommandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] = {
    CommandHandlerBuilder.builder[Command, Event, State]()
  }

  /**
   * @return A new, mutable, event handler builder
   */
  protected final def newEventHandlerBuilder(): EventHandlerBuilder[State, Event] =
    EventHandlerBuilder.builder[State, Event]()

  /**
   * Override and define the journal plugin id that this actor should use instead of the default.
   */
  def journalPluginId: String = ""

  /**
   * Override and define the snapshot store plugin id that this actor should use instead of the default.
   */
  def snapshotPluginId: String = ""

  /**
   * Override and define the snapshot selection criteria used by this actor instead of the default.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip replaying snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def snapshotSelectionCriteria: SnapshotSelectionCriteria = SnapshotSelectionCriteria.latest

  /**
   * Initiates a snapshot if the given predicate evaluates to true.
   *
   * Decide to store a snapshot based on the State, Event and sequenceNr when the event has
   * been successfully persisted.
   *
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * Snapshots triggered by `snapshotWhen` will not trigger deletes of old snapshots and events if
   * [[EventSourcedBehavior.retentionCriteria]] with [[RetentionCriteria.snapshotEvery]] is used together with
   * `shouldSnapshot`. Such deletes are only triggered by snapshots matching the `numberOfEvents` in the
   * [[RetentionCriteria]].
   *
   * @return `true` if snapshot should be saved at the given `state`, `event` and `sequenceNr` when the event has
   *         been successfully persisted
   */
  def shouldSnapshot(@unused state: State, @unused event: Event, @unused sequenceNr: Long): Boolean = false

  /**
   * Criteria for retention/deletion of snapshots and events.
   * By default, retention is disabled and snapshots are not saved and deleted automatically.
   */
  def retentionCriteria: RetentionCriteria = RetentionCriteria.disabled

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def tagsFor(@unused event: Event): java.util.Set[String] = Collections.emptySet()

  def eventAdapter(): EventAdapter[Event, _] = NoOpEventAdapter.instance[Event]

  /**
   * INTERNAL API: DeferredBehavior init
   */
  @InternalApi override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] = {
    val snapshotWhen: (State, Event, Long) => Boolean = (state, event, seqNr) => shouldSnapshot(state, event, seqNr)

    val tagger: Event => Set[String] = { event =>
      import akka.util.ccompat.JavaConverters._
      val tags = tagsFor(event)
      if (tags.isEmpty) Set.empty
      else tags.asScala.toSet
    }

    val behavior = new internal.EventSourcedBehaviorImpl[Command, Event, State](
      persistenceId,
      emptyState,
      (state, cmd) => commandHandler()(state, cmd).asInstanceOf[EffectImpl[Event, State]],
      eventHandler()(_, _),
      getClass)
      .snapshotWhen(snapshotWhen)
      .withRetention(retentionCriteria.asScala)
      .withTagger(tagger)
      .eventAdapter(eventAdapter())
      .withJournalPluginId(journalPluginId)
      .withSnapshotPluginId(snapshotPluginId)
      .withSnapshotSelectionCriteria(snapshotSelectionCriteria)

    val handler = signalHandler()
    val behaviorWithSignalHandler =
      if (handler.isEmpty) behavior
      else behavior.receiveSignal(handler.handler)

    if (onPersistFailure.isPresent)
      behaviorWithSignalHandler.onPersistFailure(onPersistFailure.get)
    else
      behaviorWithSignalHandler
  }

  /**
   * The last sequence number that was persisted, can only be called from inside the handlers of an `EventSourcedBehavior`
   */
  final def lastSequenceNumber(ctx: ActorContext[_]): Long = {
    scaladsl.EventSourcedBehavior.lastSequenceNumber(ctx.asScala)
  }

}

/**
 * A [[EventSourcedBehavior]] that is enforcing that replies to commands are not forgotten.
 * There will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
 * created with `Effects().reply`, `Effects().noReply`, [[Effect.thenReply]], or [[Effect.thenNoReply]].
 */
abstract class EventSourcedBehaviorWithEnforcedReplies[Command, Event, State](
    persistenceId: PersistenceId,
    backoffSupervisorStrategy: Optional[BackoffSupervisorStrategy])
    extends EventSourcedBehavior[Command, Event, State](persistenceId, backoffSupervisorStrategy) {

  def this(persistenceId: PersistenceId) = {
    this(persistenceId, Optional.empty[BackoffSupervisorStrategy])
  }

  def this(persistenceId: PersistenceId, backoffSupervisorStrategy: BackoffSupervisorStrategy) = {
    this(persistenceId, Optional.ofNullable(backoffSupervisorStrategy))
  }

  /**
   * Implement by handling incoming commands and return an `Effect()` to persist or signal other effects
   * of the command handling such as stopping the behavior or others.
   *
   * Use [[EventSourcedBehaviorWithEnforcedReplies#newCommandHandlerWithReplyBuilder]] to define the command handlers.
   *
   * The command handlers are only invoked when the actor is running (i.e. not replaying).
   * While the actor is persisting events, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  override protected def commandHandler(): CommandHandlerWithReply[Command, Event, State]

  /**
   * @return A new, mutable, command handler builder
   */
  protected def newCommandHandlerWithReplyBuilder(): CommandHandlerWithReplyBuilder[Command, Event, State] = {
    CommandHandlerWithReplyBuilder.builder[Command, Event, State]()
  }

  /**
   * Use [[EventSourcedBehaviorWithEnforcedReplies#newCommandHandlerWithReplyBuilder]] instead, or
   * extend [[EventSourcedBehavior]] instead of [[EventSourcedBehaviorWithEnforcedReplies]].
   *
   * @throws UnsupportedOperationException use newCommandHandlerWithReplyBuilder instead
   */
  override protected def newCommandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] =
    throw new UnsupportedOperationException("Use newCommandHandlerWithReplyBuilder instead")

}
