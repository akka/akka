/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.persistence.scaladsl

import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.typed.Behavior.UntypedBehavior
import akka.typed.Signal
import akka.typed.persistence.internal.PersistentActorImpl
import akka.typed.scaladsl.ActorContext

object PersistentActor {
  def persistent[Command, Event, State](
    persistenceId: String,
    initialState:  State,
    actions:       Actions[Command, Event, State],
    onEvent:       (Event, State) ⇒ State): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceId, initialState, actions, onEvent,
      recoveryCompleted = (state, _) ⇒ state)

  sealed abstract class PersistentEffect[+Event, State]() {
    def andThen(callback: State ⇒ Unit): PersistentEffect[Event, State]
  }

  final case class PersistNothing[Event, State](callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
    def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
  }

  case class Persist[Event, State](event: Event, callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
    def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
  }

  case class Unhandled[Event, State](callbacks: List[State ⇒ Unit] = Nil) extends PersistentEffect[Event, State] {
    def andThen(callback: State ⇒ Unit) = copy(callbacks = callback :: callbacks)
  }

  trait PersistentActorContext[Command, Event, State] extends ActorContext[Command] {
    self: akka.typed.javadsl.ActorContext[Command] ⇒
    def persist(event: Event): Persist[Event, State]
  }

  type CommandHandler[Command, Event, State] = Function3[Command, State, PersistentActorContext[Command, Event, State], PersistentEffect[Event, State]]
  type SignalHandler[Command, Event, State] = PartialFunction[(Signal, State, PersistentActorContext[Command, Event, State]), PersistentEffect[Event, State]]

  /**
   * `Actions` defines command handlers and partial function for other signals,
   * e.g. `Termination` messages if `watch` is used.
   *
   * Note that you can have different actions based on current state by using
   * [[Actions#byState]].
   */
  object Actions {
    def apply[Command, Event, State](commandHandler: CommandHandler[Command, Event, State]): Actions[Command, Event, State] =
      new Actions(commandHandler, Map.empty)

    /**
     * Convenience for simple commands that don't need the state and context.
     */
    def command[Command, Event, State](commandHandler: Command ⇒ PersistentEffect[Event, State]): Actions[Command, Event, State] =
      apply((cmd, _, _) ⇒ commandHandler(cmd))

    /**
     * Select different actions based on current state.
     */
    def byState[Command, Event, State](choice: State ⇒ Actions[Command, Event, State]): Actions[Command, Event, State] =
      new ByStateActions(choice, signalHandler = PartialFunction.empty)

  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final class ByStateActions[Command, Event, State](
    choice:        State ⇒ Actions[Command, Event, State],
    signalHandler: SignalHandler[Command, Event, State])
    extends Actions[Command, Event, State](
      commandHandler = (cmd, state, ctx) ⇒ choice(state).commandHandler(cmd, state, ctx),
      signalHandler) {

    // SignalHandler may be registered in the wrapper or in the wrapped
    private[akka] override def sigHandler(state: State): SignalHandler[Command, Event, State] =
      choice(state).sigHandler(state).orElse(signalHandler)

    // override to preserve the ByStateActions
    private[akka] override def withSignalHandler(
      handler: SignalHandler[Command, Event, State]): Actions[Command, Event, State] =
      new ByStateActions(choice, handler)

  }

  /**
   * `Actions` defines command handlers and partial function for other signals,
   * e.g. `Termination` messages if `watch` is used.
   * `Actions` is an immutable class.
   */
  @DoNotInherit class Actions[Command, Event, State] private[akka] (
    val commandHandler: CommandHandler[Command, Event, State],
    val signalHandler:  SignalHandler[Command, Event, State]) {

    @InternalApi private[akka] def sigHandler(state: State): SignalHandler[Command, Event, State] =
      signalHandler

    def onSignal(handler: SignalHandler[Command, Event, State]): Actions[Command, Event, State] =
      withSignalHandler(signalHandler.orElse(handler))

    /** INTERNAL API */
    @InternalApi private[akka] def withSignalHandler(
      handler: SignalHandler[Command, Event, State]): Actions[Command, Event, State] =
      new Actions(commandHandler, handler)

  }

}

class PersistentBehavior[Command, Event, State](
  val persistenceId:     String,
  val initialState:      State,
  val actions:           PersistentActor.Actions[Command, Event, State],
  val onEvent:           (Event, State) ⇒ State,
  val recoveryCompleted: (State, ActorContext[Command]) ⇒ State) extends UntypedBehavior[Command] {
  import PersistentActor._

  /** INTERNAL API */
  @InternalApi private[akka] override def untypedProps: akka.actor.Props = PersistentActorImpl.props(() ⇒ this)

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (State, ActorContext[Command]) ⇒ State): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  def snapshotOnState(predicate: State ⇒ Boolean): PersistentBehavior[Command, Event, State] = ??? // FIXME

  def snapshotOn(predicate: (State, Event) ⇒ Boolean): PersistentBehavior[Command, Event, State] = ??? // FIXME

  private def copy(
    persistenceId:     String                                 = persistenceId,
    initialState:      State                                  = initialState,
    actions:           Actions[Command, Event, State]         = actions,
    onEvent:           (Event, State) ⇒ State                 = onEvent,
    recoveryCompleted: (State, ActorContext[Command]) ⇒ State = recoveryCompleted): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceId, initialState, actions, onEvent, recoveryCompleted)
}
