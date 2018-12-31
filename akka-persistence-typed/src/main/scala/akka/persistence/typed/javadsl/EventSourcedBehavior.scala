/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.function.Predicate
import java.util.{ Collections, Optional }

import akka.actor.typed
import akka.actor.typed.{ BackoffSupervisorStrategy, Behavior }
import akka.actor.typed.Behavior.DeferredBehavior
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.persistence.SnapshotMetadata
import akka.persistence.typed.{ EventAdapter, _ }
import akka.persistence.typed.internal._

import scala.util.{ Failure, Success }

@ApiMayChange
abstract class EventSourcedBehavior[Command, Event, State >: Null] private[akka] (val persistenceId: PersistenceId, supervisorStrategy: Optional[BackoffSupervisorStrategy]) extends DeferredBehavior[Command] {

  def this(persistenceId: PersistenceId) = {
    this(persistenceId, Optional.empty[BackoffSupervisorStrategy])
  }

  def this(persistenceId: PersistenceId, backoffSupervisorStrategy: BackoffSupervisorStrategy) = {
    this(persistenceId, Optional.ofNullable(backoffSupervisorStrategy))
  }

  /**
   * Factory of effects.
   *
   * Return effects from your handlers in order to instruct persistence on how to act on the incoming message (i.e. persist events).
   */
  protected final def Effect: EffectFactories[Command, Event, State] =
    EffectFactories.asInstanceOf[EffectFactories[Command, Event, State]]

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
   * The command handlers are only invoked when the actor is running (i.e. not replaying).
   * While the actor is persisting events, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  protected def commandHandler(): CommandHandler[Command, Event, State]

  /**
   * Implement by applying the event to the current state in order to return a new state.
   *
   * The event handlers are invoked during recovery as well as running operation of this behavior,
   * in order to keep updating the state state.
   *
   * For that reason it is strongly discouraged to perform side-effects in this handler;
   * Side effects should be executed in `andThen` or `recoveryCompleted` blocks.
   */
  protected def eventHandler(): EventHandler[State, Event]

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @return A new, mutable, command handler builder
   */
  protected final def commandHandlerBuilder[S <: State](stateClass: Class[S]): CommandHandlerBuilder[Command, Event, S, State] =
    CommandHandlerBuilder.builder[Command, Event, S, State](stateClass)

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       *                       useful for example when state type is an Optional
   * @return A new, mutable, command handler builder
   */
  protected final def commandHandlerBuilder(statePredicate: Predicate[State]): CommandHandlerBuilder[Command, Event, State, State] =
    CommandHandlerBuilder.builder[Command, Event, State](statePredicate)

  /**
   * @return A new, mutable, event handler builder
   */
  protected final def eventHandlerBuilder(): EventHandlerBuilder[State, Event] =
    EventHandlerBuilder.builder[State, Event]()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(state: State): Unit = ()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * has failed
   */
  def onRecoveryFailure(failure: Throwable): Unit = ()

  /**
   * Override to get notified when a snapshot is finished.
   *
   * @param result None if successful otherwise contains the exception thrown when snapshotting
   */
  def onSnapshot(meta: SnapshotMetadata, result: Optional[Throwable]): Unit = ()

  /**
   * Override and define that snapshot should be saved every N events.
   *
   * If this is overridden `shouldSnapshot` is not used.
   *
   * @return number of events between snapshots, should be greater than 0
   * @see [[EventSourcedBehavior#shouldSnapshot]]
   */
  def snapshotEvery(): Long = 0L

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * receives the State, Event and the sequenceNr used for the Event
   *
   * @return `true` if snapshot should be saved for the given event
   * @see [[EventSourcedBehavior#snapshotEvery]]
   */
  def shouldSnapshot(state: State, event: Event, sequenceNr: Long): Boolean = false

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def tagsFor(event: Event): java.util.Set[String] = Collections.emptySet()

  def eventAdapter(): EventAdapter[Event, _] = NoOpEventAdapter.instance[Event]

  /**
   * INTERNAL API: DeferredBehavior init
   */
  @InternalApi override def apply(context: typed.TypedActorContext[Command]): Behavior[Command] = {

    val snapshotWhen: (State, Event, Long) ⇒ Boolean = { (state, event, seqNr) ⇒
      val n = snapshotEvery()
      if (n > 0)
        seqNr % n == 0
      else
        shouldSnapshot(state, event, seqNr)
    }

    val tagger: Event ⇒ Set[String] = { event ⇒
      import scala.collection.JavaConverters._
      val tags = tagsFor(event)
      if (tags.isEmpty) Set.empty
      else tags.asScala.toSet
    }

    val behavior = scaladsl.EventSourcedBehavior[Command, Event, State](
      persistenceId,
      emptyState,
      (state, cmd) ⇒ commandHandler()(state, cmd).asInstanceOf[EffectImpl[Event, State]],
      eventHandler()(_, _))
      .onRecoveryCompleted(onRecoveryCompleted)
      .snapshotWhen(snapshotWhen)
      .withTagger(tagger)
      .onSnapshot((meta, result) ⇒ {
        result match {
          case Success(_) ⇒
            context.asScala.log.debug("Save snapshot successful, snapshot metadata: [{}]", meta)
          case Failure(e) ⇒
            context.asScala.log.error(e, "Save snapshot failed, snapshot metadata: [{}]", meta)
        }

        onSnapshot(meta, result match {
          case Success(_) ⇒ Optional.empty()
          case Failure(t) ⇒ Optional.of(t)
        })
      })
      .eventAdapter(eventAdapter())
      .onRecoveryFailure(onRecoveryFailure)

    if (supervisorStrategy.isPresent)
      behavior.onPersistFailure(supervisorStrategy.get)
    else
      behavior
  }

}

/**
 * FIXME This is not completed for javadsl yet. The compiler is not enforcing the replies yet.
 *
 * A [[EventSourcedBehavior]] that is enforcing that replies to commands are not forgotten.
 * There will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
 * created with `Effects().reply`, `Effects().noReply`, [[Effect.thenReply]], or [[Effect.thenNoReply]].
 */
@ApiMayChange
abstract class EventSourcedBehaviorWithEnforcedReplies[Command, Event, State >: Null](persistenceId: PersistenceId, backoffSupervisorStrategy: Optional[BackoffSupervisorStrategy])
  extends EventSourcedBehavior[Command, Event, State](persistenceId, backoffSupervisorStrategy) {

  def this(persistenceId: PersistenceId) = {
    this(persistenceId, Optional.empty[BackoffSupervisorStrategy])
  }

  def this(persistenceId: PersistenceId, backoffSupervisorStrategy: BackoffSupervisorStrategy) = {
    this(persistenceId, Optional.ofNullable(backoffSupervisorStrategy))
  }

  // FIXME override commandHandler and commandHandlerBuilder to require the ReplyEffect return type,
  // which is unfortunately intrusive to the CommandHandlerBuilder
}
