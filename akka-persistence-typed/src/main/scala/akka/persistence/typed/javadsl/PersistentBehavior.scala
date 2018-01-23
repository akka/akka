/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.javadsl

import java.util.Collections

import akka.actor.typed.Behavior.UntypedPropsBehavior
import akka.actor.typed.internal.adapter.PropsAdapter
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.persistence.typed._
import akka.persistence.typed.internal._

/** Java API */
@ApiMayChange
abstract class PersistentBehavior[Command, Event, State >: Null](val persistenceId: String) extends UntypedPropsBehavior[Command] {

  /**
   * Factory of effects.
   *
   * Return effects from your handlers in order to instruct persistence on how to act on the incoming message (i.e. persist events).
   */
  protected final def Effect: EffectFactories[Command, Event, State] = EffectFactory.asInstanceOf[EffectFactories[Command, Event, State]]

  /**
   * Implement by returning the initial state object.
   * This object will be passed into this behaviors handlers, until a new state replaces it.
   *
   * Also known as "zero state" or "neutral state".
   */
  protected def initialState: State

  /**
   * Implement by handling incoming commands and return an `Effect()` to persist or signal other effects
   * of the command handling such as stopping the behavior or others.
   *
   * This method is only invoked when the actor is running (i.e. not recovering).
   * While the actor is persisting events, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  protected def commandHandler(): CommandHandler[Command, Event, State]

  /**
   * Implement by applying the event to the current state in order to return a new state.
   *
   * This method invoked during recovery as well as running operation of this behavior,
   * in order to keep updating the state state.
   *
   * For that reason it is strongly discouraged to perform side-effects in this handler;
   * Side effects should be executed in `andThen` or `recoveryCompleted` blocks.
   */
  protected def eventHandler(): EventHandler[Event, State]

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def commandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def byStateCommandHandlerBuilder(): ByStateCommandHandlerBuilder[Command, Event, State] =
    new ByStateCommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, event handler builder
   */
  protected final def eventHandlerBuilder(): EventHandlerBuilder[Event, State] =
    new EventHandlerBuilder[Event, State]()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(ctx: ActorContext[Command], state: State): Unit = {}

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def shouldSnapshot(state: State, event: Event, sequenceNr: Long): Boolean = false
  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def tagsFor(event: Event): java.util.Set[String] = Collections.emptySet()

  /** INTERNAL API */
  @InternalApi private[akka] override def untypedProps(props: akka.actor.typed.Props): akka.actor.Props = {
    val behaviorImpl = scaladsl.PersistentBehaviors.immutable[Command, Event, State](
      persistenceId,
      initialState,
      (c, state, cmd) ⇒ commandHandler()(c.asJava, state, cmd).asInstanceOf[EffectImpl[Event, State]],
      eventHandler()(_, _)
    )

    PropsAdapter(() ⇒ behaviorImpl, props)
  }

}

