/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.persistence.scaladsl

import scala.collection.{ immutable ⇒ im }
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.typed.Behavior.UntypedBehavior
import akka.typed.Signal
import akka.typed.persistence.internal.PersistentActorImpl
import akka.typed.scaladsl.ActorContext

object PersistentActor {
  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId: String,
    initialState:  State,
    actions:       Actions[Command, Event, State],
    applyEvent:    (Event, State) ⇒ State): PersistentBehavior[Command, Event, State] =
    persistentEntity(_ ⇒ persistenceId, initialState, actions, applyEvent)

  /**
   * Create a `Behavior` for a persistent actor in Cluster Sharding, when the persistenceId is not known
   * until the actor is started and typically based on the entityId, which
   * is the actor name.
   *
   * TODO This will not be needed when it can be wrapped in `Actor.deferred`.
   */
  def persistentEntity[Command, Event, State](
    persistenceIdFromActorName: String ⇒ String,
    initialState:               State,
    actions:                    Actions[Command, Event, State],
    applyEvent:                 (Event, State) ⇒ State): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceIdFromActorName, initialState, actions, applyEvent,
      recoveryCompleted = (_, state) ⇒ state)

  sealed abstract class Effect[+Event, State]() {
    /* All events that will be persisted in this effect */
    def events: im.Seq[Event] = Nil

    /* All side effects that will be performed in this effect */
    def sideEffects: im.Seq[ChainableEffect[_, State]] =
      if (isInstanceOf[ChainableEffect[_, State]]) im.Seq(asInstanceOf[ChainableEffect[_, State]])
      else Nil

    def andThen(sideEffects: ChainableEffect[_, State]*): Effect[Event, State] =
      CompositeEffect(if (events.isEmpty) None else Some(this), sideEffects.toList)

    /** Convenience method to register a side effect with just a callback function */
    def andThen(callback: State ⇒ Unit): Effect[Event, State] =
      andThen(SideEffect[Event, State](callback))

    /** Convenience method to register a side effect with just a lazy expression */
    def andThen(callback: ⇒ Unit): Effect[Event, State] =
      andThen(SideEffect[Event, State]((_: State) ⇒ callback))
  }

  case class CompositeEffect[Event, State](persistingEffect: Option[Effect[Event, State]], override val sideEffects: im.Seq[ChainableEffect[_, State]]) extends Effect[Event, State] {
    override val events = persistingEffect.map(_.events).getOrElse(Nil)
    override def andThen(additionalSideEffects: ChainableEffect[_, State]*): CompositeEffect[Event, State] =
      copy(sideEffects = sideEffects ++ additionalSideEffects)
  }
  object CompositeEffect {
    def apply[Event, State](persistAll: PersistAll[Event, State], sideEffects: im.Seq[ChainableEffect[_, State]]): CompositeEffect[Event, State] =
      CompositeEffect(Some(persistAll), sideEffects)
  }

  case class PersistNothing[Event, State]() extends Effect[Event, State]

  case class Persist[Event, State](event: Event) extends Effect[Event, State] {
    override val events = event :: Nil
  }
  case class PersistAll[Event, State](override val events: im.Seq[Event]) extends Effect[Event, State]

  trait ChainableEffect[Event, State] {
    self: Effect[Event, State] ⇒
  }
  case class SideEffect[Event, State](effect: State ⇒ Unit) extends Effect[Event, State] with ChainableEffect[Event, State]
  case class Stop[Event, State]() extends Effect[Event, State] with ChainableEffect[Event, State]()

  case class Unhandled[Event, State]() extends Effect[Event, State]

  type CommandHandler[Command, Event, State] = Function3[ActorContext[Command], Command, State, Effect[Event, State]]
  type SignalHandler[Command, Event, State] = PartialFunction[(ActorContext[Command], Signal, State), Effect[Event, State]]

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
    def command[Command, Event, State](commandHandler: Command ⇒ Effect[Event, State]): Actions[Command, Event, State] =
      apply((_, cmd, _) ⇒ commandHandler(cmd))

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
      commandHandler = (ctx, cmd, state) ⇒ choice(state).commandHandler(ctx, cmd, state),
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
  @InternalApi private[akka] val persistenceIdFromActorName: String ⇒ String,
  val initialState:                                          State,
  val actions:                                               PersistentActor.Actions[Command, Event, State],
  val applyEvent:                                            (Event, State) ⇒ State,
  val recoveryCompleted:                                     (ActorContext[Command], State) ⇒ State) extends UntypedBehavior[Command] {
  import PersistentActor._

  /** INTERNAL API */
  @InternalApi private[akka] override def untypedProps: akka.actor.Props = PersistentActorImpl.props(() ⇒ this)

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ State): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  /**
   * FIXME snapshots are not implemented yet, this is only an API placeholder
   */
  def snapshotOnState(predicate: State ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???

  /**
   * FIXME snapshots are not implemented yet, this is only an API placeholder
   */
  def snapshotOn(predicate: (State, Event) ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???

  private def copy(
    persistenceIdFromActorName: String ⇒ String                        = persistenceIdFromActorName,
    initialState:               State                                  = initialState,
    actions:                    Actions[Command, Event, State]         = actions,
    applyEvent:                 (Event, State) ⇒ State                 = applyEvent,
    recoveryCompleted:          (ActorContext[Command], State) ⇒ State = recoveryCompleted): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceIdFromActorName, initialState, actions, applyEvent, recoveryCompleted)
}
