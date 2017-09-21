/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.persistence.scaladsl

import scala.collection.immutable
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
    /* All events that will be persisted in this effect */
    def events: immutable.Seq[Event] = Nil

    /* All side effects that will be performed in this effect */
    def sideEffects: immutable.Seq[ChainableEffect[_, State]] =
      if (isInstanceOf[ChainableEffect[_, State]]) immutable.Seq(asInstanceOf[ChainableEffect[_, State]])
      else Nil

    def andThen(sideEffects: ChainableEffect[_, State]*): PersistentEffect[Event, State] =
      CompositeEffect(if (events.isEmpty) None else Some(this), sideEffects.toList)

    /** Convenience method to register a side effect with just a callback function */
    def andThen(callback: State ⇒ Unit): PersistentEffect[Event, State] =
      andThen(SideEffect[Event, State](callback))
  }

  case class CompositeEffect[Event, State](persistingEffect: Option[PersistentEffect[Event, State]], override val sideEffects: immutable.Seq[ChainableEffect[_, State]]) extends PersistentEffect[Event, State] {
    override val events = persistingEffect.map(_.events).getOrElse(Nil)
    override def andThen(additionalSideEffects: ChainableEffect[_, State]*): CompositeEffect[Event, State] =
      copy(sideEffects = sideEffects ++ additionalSideEffects)
  }
  object CompositeEffect {
    def apply[Event, State](persistAll: PersistAll[Event, State], sideEffects: immutable.Seq[ChainableEffect[_, State]]): CompositeEffect[Event, State] =
      CompositeEffect(Some(persistAll), sideEffects)
  }

  case class PersistNothing[Event, State]() extends PersistentEffect[Event, State]

  case class Persist[Event, State](event: Event) extends PersistentEffect[Event, State] {
    override val events = event :: Nil
  }
  case class PersistAll[Event, State](override val events: immutable.Seq[Event]) extends PersistentEffect[Event, State]

  trait ChainableEffect[Event, State] {
    self: PersistentEffect[Event, State] ⇒
  }
  case class SideEffect[Event, State](effect: State ⇒ Unit) extends PersistentEffect[Event, State] with ChainableEffect[Event, State]
  case class Stop[Event, State]() extends PersistentEffect[Event, State] with ChainableEffect[Event, State]()

  case class Unhandled[Event, State]() extends PersistentEffect[Event, State]

  type CommandHandler[Command, Event, State] = Function3[Command, State, ActorContext[Command], PersistentEffect[Event, State]]
  type SignalHandler[Command, Event, State] = PartialFunction[(Signal, State, ActorContext[Command]), PersistentEffect[Event, State]]

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
