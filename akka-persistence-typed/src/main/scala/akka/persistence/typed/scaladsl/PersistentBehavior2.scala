/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed
import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.DeferredBehavior
import akka.annotation.InternalApi

/*
API experiment with abstract class.
- commandHandler and eventHandler defined as functions inside enclosing class
- see AccountExample2b
 */

// FIXME why is >: Null needed?

abstract class PersistentBehavior2[Command, Event, State >: Null] private (val persistenceId: String, supervisorStrategy: Option[BackoffSupervisorStrategy])
  extends DeferredBehavior[Command] {

  def this(persistenceId: String) = {
    this(persistenceId, None)
  }

  def this(persistenceId: String, backoffSupervisorStrategy: BackoffSupervisorStrategy) = {
    this(persistenceId, Some(backoffSupervisorStrategy))
  }

  /**
   * Type alias for the command handler function that defines how to act on commands.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type CommandHandler = (State, Command) ⇒ Effect[Event, State]

  /**
   * Type alias for the command handler function for a subclass of the `State` that defines how to act on commands.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type SubStateCommandHandler[S <: State] = (S, Command) ⇒ Effect[Event, State]

  /**
   * Type alias for the event handler for updating the state based on events having been persisted.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type EventHandler = (State, Event) ⇒ State

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
  protected def commandHandler(): (State, Command) ⇒ Effect[Event, State]

  /**
   * Convenience for simple commands that don't need the state.
   *
   * @see [[Effect]] for possible effects of a command.
   */
  protected def command(handler: Command ⇒ Effect[Event, State]): (State, Command) ⇒ Effect[Event, State] =
    (_, cmd) ⇒ handler(cmd)

  /**
   * Implement by applying the event to the current state in order to return a new state.
   *
   * The event handlers are invoked during recovery as well as running operation of this behavior,
   * in order to keep updating the state state.
   *
   * For that reason it is strongly discouraged to perform side-effects in this handler;
   * Side effects should be executed in `andThen` or `recoveryCompleted` blocks.
   */
  protected def eventHandler(): (State, Event) ⇒ State

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(state: State): Unit = ()

  // FIXME also snapshot and tags stuff

  /**
   * INTERNAL API: DeferredBehavior init
   */
  @InternalApi override def apply(context: typed.ActorContext[Command]): Behavior[Command] = ??? // FIXME

}
