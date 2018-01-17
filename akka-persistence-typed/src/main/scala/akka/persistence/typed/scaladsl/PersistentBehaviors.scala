/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.scaladsl

import akka.actor.typed.Behavior.UntypedBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.persistence.typed.internal.PersistentActorImpl

import scala.collection.{ immutable ⇒ im }

object PersistentBehaviors {

  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] =
    persistentEntity(_ ⇒ persistenceId, initialState, commandHandler, eventHandler)

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
    commandHandler:             CommandHandler[Command, Event, State],
    eventHandler:               (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceIdFromActorName, initialState, commandHandler, eventHandler,
      recoveryCompleted = (_, _) ⇒ (),
      tagger = _ ⇒ Set.empty)

  /**
   * Factories for effects - how a persistent actor reacts on a command
   */
  object Effect {

    def persist[Event, State](event: Event): Effect[Event, State] =
      Persist(event)

    def persist[Event, A <: Event, B <: Event, State](evt1: A, evt2: B, events: Event*): Effect[Event, State] =
      persist(evt1 :: evt2 :: events.toList)

    def persist[Event, State](eventOpt: Option[Event]): Effect[Event, State] =
      eventOpt match {
        case Some(evt) ⇒ persist[Event, State](evt)
        case _         ⇒ none[Event, State]
      }

    def persist[Event, State](events: im.Seq[Event]): Effect[Event, State] =
      PersistAll(events)

    def persist[Event, State](events: im.Seq[Event], sideEffects: im.Seq[ChainableEffect[Event, State]]): Effect[Event, State] =
      new CompositeEffect[Event, State](Some(new PersistAll[Event, State](events)), sideEffects)

    /**
     * Do not persist anything
     */
    def none[Event, State]: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

    /**
     * This command is not handled, but it is not an error that it isn't.
     */
    def unhandled[Event, State]: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]

    /**
     * Stop this persistent actor
     */
    def stop[Event, State]: ChainableEffect[Event, State] = Stop.asInstanceOf[ChainableEffect[Event, State]]
  }

  /**
   * Instances are created through the factories in the [[Effect]] companion object.
   *
   * Not for user extension.
   */
  @DoNotInherit
  sealed abstract class Effect[+Event, State] {
    /* All events that will be persisted in this effect */
    def events: im.Seq[Event] = Nil

    /* All side effects that will be performed in this effect */
    def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = Nil

    /** Convenience method to register a side effect with just a callback function */
    def andThen(callback: State ⇒ Unit): Effect[Event, State] =
      CompositeEffect(this, SideEffect[Event, State](callback))

    /** Convenience method to register a side effect with just a lazy expression */
    def andThen(callback: ⇒ Unit): Effect[Event, State] =
      CompositeEffect(this, SideEffect[Event, State]((_: State) ⇒ callback))

    /** The side effect is to stop the actor */
    def andThenStop: Effect[Event, State] =
      CompositeEffect(this, Effect.stop[Event, State])
  }

  @InternalApi
  private[akka] object CompositeEffect {
    def apply[Event, State](effect: Effect[Event, State], sideEffects: ChainableEffect[Event, State]): Effect[Event, State] =
      CompositeEffect[Event, State](
        if (effect.events.isEmpty) None else Some(effect),
        sideEffects :: Nil
      )
  }

  @InternalApi
  private[akka] final case class CompositeEffect[Event, State](
    persistingEffect: Option[Effect[Event, State]],
    _sideEffects:     im.Seq[ChainableEffect[Event, State]]) extends Effect[Event, State] {
    override val events = persistingEffect.map(_.events).getOrElse(Nil)

    override def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = _sideEffects.asInstanceOf[im.Seq[ChainableEffect[E, State]]]

  }

  @InternalApi
  private[akka] case object PersistNothing extends Effect[Nothing, Nothing]

  @InternalApi
  private[akka] case class Persist[Event, State](event: Event) extends Effect[Event, State] {
    override def events = event :: Nil
  }
  @InternalApi
  private[akka] case class PersistAll[Event, State](override val events: im.Seq[Event]) extends Effect[Event, State]

  /**
   * Not for user extension
   */
  @DoNotInherit
  sealed abstract class ChainableEffect[Event, State] extends Effect[Event, State]
  @InternalApi
  private[akka] case class SideEffect[Event, State](effect: State ⇒ Unit) extends ChainableEffect[Event, State]
  @InternalApi
  private[akka] case object Stop extends ChainableEffect[Nothing, Nothing]

  @InternalApi
  private[akka] case object Unhandled extends Effect[Nothing, Nothing]

  type CommandHandler[Command, Event, State] = (ActorContext[Command], State, Command) ⇒ Effect[Event, State]

  /**
   * The `CommandHandler` defines how to act on commands.
   *
   * Note that you can have different command handlers based on current state by using
   * [[CommandHandler#byState]].
   */
  object CommandHandler {

    /**
     * Convenience for simple commands that don't need the state and context.
     *
     * @see [[Effect]] for possible effects of a command.
     */
    def command[Command, Event, State](commandHandler: Command ⇒ Effect[Event, State]): CommandHandler[Command, Event, State] =
      (_, _, cmd) ⇒ commandHandler(cmd)

    /**
     * Select different command handlers based on current state.
     */
    def byState[Command, Event, State](choice: State ⇒ CommandHandler[Command, Event, State]): CommandHandler[Command, Event, State] =
      new ByStateCommandHandler(choice)

  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final class ByStateCommandHandler[Command, Event, State](
    choice: State ⇒ CommandHandler[Command, Event, State])
    extends CommandHandler[Command, Event, State] {

    override def apply(ctx: ActorContext[Command], state: State, cmd: Command): Effect[Event, State] =
      choice(state)(ctx, state, cmd)

  }
}

class PersistentBehavior[Command, Event, State](
  @InternalApi private[akka] val persistenceIdFromActorName: String ⇒ String,
  val initialState:                                          State,
  val commandHandler:                                        PersistentBehaviors.CommandHandler[Command, Event, State],
  val eventHandler:                                          (State, Event) ⇒ State,
  val recoveryCompleted:                                     (ActorContext[Command], State) ⇒ Unit,
  val tagger:                                                Event ⇒ Set[String]) extends UntypedBehavior[Command] {
  import PersistentBehaviors._

  /** INTERNAL API */
  @InternalApi private[akka] override def untypedProps: akka.actor.Props = PersistentActorImpl.props(() ⇒ this)

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  /**
   * FIXME snapshots are not implemented yet, this is only an API placeholder
   */
  def snapshotOnState(predicate: State ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???

  /**
   * FIXME snapshots are not implemented yet, this is only an API placeholder
   */
  def snapshotOn(predicate: (State, Event) ⇒ Boolean): PersistentBehavior[Command, Event, State] = ???

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State] =
    copy(tagger = tagger)

  private def copy(
    persistenceIdFromActorName: String ⇒ String                       = persistenceIdFromActorName,
    initialState:               State                                 = initialState,
    commandHandler:             CommandHandler[Command, Event, State] = commandHandler,
    eventHandler:               (State, Event) ⇒ State                = eventHandler,
    recoveryCompleted:          (ActorContext[Command], State) ⇒ Unit = recoveryCompleted,
    tagger:                     Event ⇒ Set[String]                   = tagger): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceIdFromActorName, initialState, commandHandler, eventHandler, recoveryCompleted, tagger)
}
