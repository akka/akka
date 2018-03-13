/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import akka.actor.typed.javadsl.ActorContext
import akka.annotation.InternalApi
import akka.japi.pf.FI
import akka.persistence.typed.internal._
import akka.util.OptionVal

/**
 * FunctionalInterface for reacting on commands
 *
 * Used with [[CommandHandlerBuilder]] to setup the behavior of a [[PersistentBehavior]]
 */
@FunctionalInterface
trait CommandHandler[Command, Event, State] {
  def apply(ctx: ActorContext[Command], state: State, command: Command): Effect[Event, State]
}
/**
 * FunctionalInterface for reacting on signals
 *
 * Used with [[CommandHandlerBuilder]] to setup the behavior of a [[PersistentBehavior]]
 */
@FunctionalInterface
trait CommandToEffect[Command, MsgCommand <: Command, Event, State] {
  /**
   * @return An effect for the given command
   */
  def apply(ctx: ActorContext[Command], state: State, command: MsgCommand): Effect[Event, State]
}

final class CommandHandlerBuilder[Command, Event, State] @InternalApi private[persistence] () {

  private final case class CommandHandlerCase(predicate: Command ⇒ Boolean, handler: CommandToEffect[Command, Command, Event, State])

  private var cases: List[CommandHandlerCase] = Nil

  private def addCase(predicate: Command ⇒ Boolean, handler: CommandToEffect[Command, Command, Event, State]): Unit = {
    cases = CommandHandlerCase(predicate, handler) :: cases
  }
  /**
   * Match any command which the given `predicate` returns true for
   */
  def matchCommand(predicate: FI.TypedPredicate[Command], commandToEffect: CommandToEffect[Command, Command, Event, State]): CommandHandlerBuilder[Command, Event, State] = {
    addCase(cmd ⇒ predicate.defined(cmd), commandToEffect)
    this
  }

  def matchCommand[C <: Command](commandClass: Class[C], commandToEffect: CommandToEffect[Command, C, Event, State]): CommandHandlerBuilder[Command, Event, State] = {
    addCase(cmd ⇒ commandClass.isAssignableFrom(cmd.getClass), commandToEffect.asInstanceOf[CommandToEffect[Command, Command, Event, State]])
    this
  }

  /**
   * Builds a Command Handler and resets this builder
   */
  def build(): CommandHandler[Command, Event, State] = {
    val builtCases = cases.reverse.toArray
    cases = Nil
    new CommandHandler[Command, Event, State] {
      override def apply(ctx: ActorContext[Command], state: State, command: Command) = {
        var idx = 0
        var effect: OptionVal[Effect[Event, State]] = OptionVal.None

        while (idx < builtCases.length && effect.isEmpty) {
          val curr = builtCases(idx)
          if (curr.predicate(command)) {
            effect = OptionVal.Some(curr.handler.apply(ctx, state, command))
          }
          idx += 1
        }

        effect match {
          case OptionVal.None    ⇒ throw new MatchError(s"No match found for command of type [${command.getClass.getName}]")
          case OptionVal.Some(e) ⇒ e.asInstanceOf[EffectImpl[Event, State]]
        }
      }
    }
  }

}
/**
 * Mutable builder for nested Java [[CommandHandler]]s where different states should have different command handlers.
 * [[CommandHandler]] per state are added with the `match` methods and finally a [[CommandHandler]] is created with [[ByStateCommandHandlerBuilder#build]].
 *
 * Match statements are appended and evaluated in order, the first one to match is used. If no match is found when
 * evaluating the built [[CommandHandler]] for a given state a [[scala.MatchError]] is thrown.
 */

final class ByStateCommandHandlerBuilder[Command, Event, State] @InternalApi private[javadsl] () {

  private final case class ByStateCase(predicate: State ⇒ Boolean, handler: CommandHandler[Command, Event, State])

  private var cases: List[ByStateCase] = Nil

  private def addCase(predicate: State ⇒ Boolean, handler: CommandHandler[Command, Event, State]): Unit = {
    cases = ByStateCase(predicate, handler) :: cases
  }

  /**
   * Match any state that the `predicate` returns true for.
   */
  def matchState(predicate: FI.TypedPredicate[State], commandHandler: CommandHandler[Command, Event, State]): ByStateCommandHandlerBuilder[Command, Event, State] = {
    addCase(
      predicate.defined,
      commandHandler.asInstanceOf[CommandHandler[Command, Event, State]])
    this
  }

  /**
   * Match any state which is an instance of `S` or a subtype of `S`
   *
   * Throws `java.lang.IllegalArgumentException` if `stateClass` is not a subtype of the root `State` this builder was created with
   */
  def matchState[S <: State](stateClass: Class[S], commandHandler: CommandHandler[Command, Event, S]): ByStateCommandHandlerBuilder[Command, Event, State] = {
    addCase(
      state ⇒ stateClass.isAssignableFrom(state.getClass),
      commandHandler.asInstanceOf[CommandHandler[Command, Event, State]])
    this
  }

  /**
   * Match any state which is an instance of `S` or a subtype of `S` and for which the `predicate` returns true
   *
   * Throws `java.lang.IllegalArgumentException`  if `stateClass` is not a subtype of the root `State` this builder was created with
   */
  def matchState[S <: State](stateClass: Class[S], predicate: FI.TypedPredicate[S], commandHandler: CommandHandler[Command, Event, S]): ByStateCommandHandlerBuilder[Command, Event, State] = {
    addCase(
      state ⇒ stateClass.isAssignableFrom(state.getClass) && predicate.defined(state.asInstanceOf[S]),
      commandHandler.asInstanceOf[CommandHandler[Command, Event, State]])
    this
  }

  /**
   * Match states that are equal to the given `state` instance
   */
  def matchExact[S <: State](state: S, commandHandler: CommandHandler[Command, Event, S]): ByStateCommandHandlerBuilder[Command, Event, State] = {
    addCase(s ⇒ s.equals(state), commandHandler.asInstanceOf[CommandHandler[Command, Event, State]])
    this
  }

  /**
   * Match any state
   *
   * Builds and returns the handler since this will not let through any states to subsequent match statements
   */
  def matchAny(commandHandler: CommandHandler[Command, Event, State]): CommandHandler[Command, Event, State] = {
    addCase(_ ⇒ true, commandHandler)
    build()
  }

  /**
   * Build a command handler from the appended cases. The returned handler will throw a [[scala.MatchError]] if applied to
   * a command that has no defined match.
   *
   * The builder is reset to empty after build has been called.
   */
  def build(): CommandHandler[Command, Event, State] = {
    val builtCases = cases.reverse.toArray
    cases = Nil
    new CommandHandler[Command, Event, State] {
      override def apply(ctx: ActorContext[Command], state: State, command: Command) = {
        var idx = 0
        var effect: OptionVal[Effect[Event, State]] = OptionVal.None

        while (idx < builtCases.length && effect.isEmpty) {
          val curr = builtCases(idx)
          if (curr.predicate(state)) {
            effect = OptionVal.Some(curr.handler.apply(ctx, state, command))
          }
          idx += 1
        }

        effect match {
          case OptionVal.None    ⇒ throw new MatchError(s"No match found for command of type [${command.getClass.getName}]")
          case OptionVal.Some(e) ⇒ e.asInstanceOf[EffectImpl[Event, State]]
        }
      }
    }
  }
}

