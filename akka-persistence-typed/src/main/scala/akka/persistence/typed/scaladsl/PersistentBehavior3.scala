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
- onCommand (commandHandler) and onEvent (eventHandler) are methods
- see also alignment with scaladsl.MutableBehavior
- see AccountExample3b
 */

// FIXME why is >: Null needed?

abstract class PersistentBehavior3[Command, Event, State >: Null] private (val persistenceId: String, supervisorStrategy: Option[BackoffSupervisorStrategy])
  extends DeferredBehavior[Command] {

  def this(persistenceId: String) = {
    this(persistenceId, None)
  }

  def this(persistenceId: String, backoffSupervisorStrategy: BackoffSupervisorStrategy) = {
    this(persistenceId, Some(backoffSupervisorStrategy))
  }

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
  protected def onCommand(state: State, command: Command): Effect[Event, State]

  /**
   * Implement by applying the event to the current state in order to return a new state.
   *
   * The event handlers are invoked during recovery as well as running operation of this behavior,
   * in order to keep updating the state state.
   *
   * For that reason it is strongly discouraged to perform side-effects in this handler;
   * Side effects should be executed in `andThen` or `recoveryCompleted` blocks.
   */
  protected def onEvent(state: State, event: Event): State

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
