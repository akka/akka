/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.javadsl

import java.util.Optional

import akka.actor.typed
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.actor.typed.internal.BehaviorImpl.DeferredBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.persistence.typed.state.internal
import akka.persistence.typed.state.internal._
import akka.persistence.typed.state.scaladsl
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.SnapshotAdapter

/**
 * A `Behavior` for a persistent actor with durable storage of its state.
 *
 * API May Change
 */
@ApiMayChange
abstract class DurableStateBehavior[Command, State] private[akka] (
    val persistenceId: PersistenceId,
    onPersistFailure: Optional[BackoffSupervisorStrategy])
    extends DeferredBehavior[Command] {

  /**
   * @param persistenceId stable unique identifier for the `DurableStateBehavior`
   */
  def this(persistenceId: PersistenceId) = {
    this(persistenceId, Optional.empty[BackoffSupervisorStrategy])
  }

  /**
   * If using onPersistFailure the supervision is only around the `DurableStateBehavior` not any outer setup/withTimers
   * block. If using restart any actions e.g. scheduling timers, can be done on the PreRestart signal or on the
   * RecoveryCompleted signal.
   *
   * @param persistenceId stable unique identifier for the `DurableStateBehavior`
   * @param onPersistFailure BackoffSupervisionStrategy for persist failures
   */
  def this(persistenceId: PersistenceId, onPersistFailure: BackoffSupervisorStrategy) = {
    this(persistenceId, Optional.ofNullable(onPersistFailure))
  }

  /**
   * Factory of effects.
   *
   * Return effects from your handlers in order to instruct persistence on how to act on the incoming message (i.e. persist state).
   */
  protected final def Effect: EffectFactories[State] =
    EffectFactories.asInstanceOf[EffectFactories[State]]

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
   * Use [[DurableStateBehavior#newCommandHandlerBuilder]] to define the command handlers.
   *
   * The command handlers are only invoked when the actor is running (i.e. not recovering).
   * While the actor is persisting state, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  protected def commandHandler(): CommandHandler[Command, State]

  /**
   * Override to react on general lifecycle signals and `DurableStateBehavior` specific signals
   * (recovery related). Those are all subtypes of [[akka.persistence.typed.state.DurableStateSignal]].
   *
   * Use [[DurableStateBehavior#newSignalHandlerBuilder]] to define the signal handler.
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
  protected def newCommandHandlerBuilder(): CommandHandlerBuilder[Command, State] = {
    CommandHandlerBuilder.builder[Command, State]()
  }

  /**
   * Override and define the `DurableStateStore` plugin id that this actor should use instead of the default.
   */
  def durableStateStorePluginId: String = ""

  /**
   * The tag that can be used in persistence query.
   */
  def tag: String = ""

  /**
   * Transform the state into another type before giving it to and from the store. Can be used
   * to migrate from different state types e.g. when migration from PersistentFSM to Typed DurableStateBehavior.
   */
  def snapshotAdapter(): SnapshotAdapter[State] = NoOpSnapshotAdapter.instance[State]

  /**
   * INTERNAL API: DeferredBehavior init, not for user extension
   */
  @InternalApi override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] =
    createDurableStateBehavior()

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final def createDurableStateBehavior(): scaladsl.DurableStateBehavior[Command, State] = {

    val behavior = new internal.DurableStateBehaviorImpl[Command, State](
      persistenceId,
      emptyState,
      (state, cmd) => commandHandler()(state, cmd).asInstanceOf[EffectImpl[State]],
      getClass).withTag(tag).snapshotAdapter(snapshotAdapter()).withDurableStateStorePluginId(durableStateStorePluginId)

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
   * The last sequence number that was persisted, can only be called from inside the handlers of a `DurableStateBehavior`
   */
  final def lastSequenceNumber(ctx: ActorContext[_]): Long = {
    scaladsl.DurableStateBehavior.lastSequenceNumber(ctx.asScala)
  }

}

/**
 * A [[DurableStateBehavior]] that is enforcing that replies to commands are not forgotten.
 * There will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
 * created with `Effects().reply`, `Effects().noReply`, [[EffectBuilder.thenReply]], or [[EffectBuilder.thenNoReply]].
 */
abstract class DurableStateBehaviorWithEnforcedReplies[Command, State](
    persistenceId: PersistenceId,
    backoffSupervisorStrategy: Optional[BackoffSupervisorStrategy])
    extends DurableStateBehavior[Command, State](persistenceId, backoffSupervisorStrategy) {

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
   * Use [[DurableStateBehaviorWithEnforcedReplies#newCommandHandlerWithReplyBuilder]] to define the command handlers.
   *
   * The command handlers are only invoked when the actor is running (i.e. not recovering).
   * While the actor is persisting state, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  override protected def commandHandler(): CommandHandlerWithReply[Command, State]

  /**
   * @return A new, mutable, command handler builder
   */
  protected def newCommandHandlerWithReplyBuilder(): CommandHandlerWithReplyBuilder[Command, State] = {
    CommandHandlerWithReplyBuilder.builder[Command, State]()
  }

  /**
   * Use [[DurableStateBehaviorWithEnforcedReplies#newCommandHandlerWithReplyBuilder]] instead, or
   * extend [[DurableStateBehavior]] instead of [[DurableStateBehaviorWithEnforcedReplies]].
   *
   * @throws UnsupportedOperationException use newCommandHandlerWithReplyBuilder instead
   */
  override protected def newCommandHandlerBuilder(): CommandHandlerBuilder[Command, State] =
    throw new UnsupportedOperationException("Use newCommandHandlerWithReplyBuilder instead")

}
