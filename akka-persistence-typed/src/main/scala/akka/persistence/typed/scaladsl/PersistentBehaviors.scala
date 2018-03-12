/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.Behavior.UntypedBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.persistence.typed.internal._

import scala.collection.{ immutable ⇒ im }

object PersistentBehaviors {

  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] = {
    // FIXME remove `persistenceIdFromActorName: String ⇒ String` from PersistentBehavior
    new PersistentBehavior(_ ⇒ persistenceId, initialState, commandHandler, eventHandler,
      recoveryCompleted = (_, _) ⇒ (),
      tagger = _ ⇒ Set.empty,
      snapshotOn = (_, _, _) ⇒ false)
  }

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
      new CompositeEffect[Event, State](PersistAll[Event, State](events), sideEffects)

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
  trait Effect[+Event, State] {
    self: EffectImpl[Event, State] ⇒
    /* All events that will be persisted in this effect */
    def events: im.Seq[Event]

    def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]]

    /** Convenience method to register a side effect with just a callback function */
    final def andThen(callback: State ⇒ Unit): Effect[Event, State] =
      CompositeEffect(this, SideEffect[Event, State](callback))

    /** Convenience method to register a side effect with just a lazy expression */
    final def andThen(callback: ⇒ Unit): Effect[Event, State] =
      CompositeEffect(this, SideEffect[Event, State]((_: State) ⇒ callback))

    /** The side effect is to stop the actor */
    def andThenStop: Effect[Event, State] =
      CompositeEffect(this, Effect.stop[Event, State])
  }

  /**
   * Not for user extension
   */
  @DoNotInherit
  abstract class ChainableEffect[Event, State] extends EffectImpl[Event, State]

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
  val tagger:                                                Event ⇒ Set[String],
  val snapshotOn:                                            (State, Event, Long) ⇒ Boolean) extends UntypedBehavior[Command] {

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
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotOn(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior[Command, Event, State] =
    copy(snapshotOn = predicate)

  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotOn = (_, _, seqNr) ⇒ seqNr % numberOfEvents == 0)
  }

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
    tagger:                     Event ⇒ Set[String]                   = tagger,
    snapshotOn:                 (State, Event, Long) ⇒ Boolean        = snapshotOn): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior(persistenceIdFromActorName, initialState, commandHandler, eventHandler, recoveryCompleted, tagger, snapshotOn)
}
