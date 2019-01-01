/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.function.BiFunction
import java.util.function.Predicate
import java.util.function.{ Function ⇒ JFunction }

import akka.annotation.InternalApi
import akka.persistence.typed.internal._
import akka.util.OptionVal

/**
 * FunctionalInterface for reacting on commands
 *
 * Used with [[CommandHandlerBuilder]] to setup the behavior of a [[EventSourcedBehavior]]
 */
@FunctionalInterface
trait CommandHandler[Command, Event, State] {
  def apply(state: State, command: Command): Effect[Event, State]
}

object CommandHandlerBuilder {

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @return A new, mutable, command handler builder
   */
  def builder[Command, Event, S <: State, State](stateClass: Class[S]): CommandHandlerBuilder[Command, Event, S, State] =
    new CommandHandlerBuilder(statePredicate = new Predicate[S] {
      override def test(state: S): Boolean = state != null && stateClass.isAssignableFrom(state.getClass)
    })

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   * @return A new, mutable, command handler builder
   */
  def builder[Command, Event, State](statePredicate: Predicate[State]): CommandHandlerBuilder[Command, Event, State, State] =
    new CommandHandlerBuilder(statePredicate)

  /**
   * INTERNAL API
   */
  @InternalApi private final case class CommandHandlerCase[Command, Event, State](
    commandPredicate: Command ⇒ Boolean,
    statePredicate:   State ⇒ Boolean,
    handler:          BiFunction[State, Command, Effect[Event, State]])
}

final class CommandHandlerBuilder[Command, Event, S <: State, State] @InternalApi private[persistence] (
  val statePredicate: Predicate[S]) {
  import CommandHandlerBuilder.CommandHandlerCase

  private var cases: List[CommandHandlerCase[Command, Event, State]] = Nil

  private def addCase(predicate: Command ⇒ Boolean, handler: BiFunction[S, Command, Effect[Event, State]]): Unit = {
    cases = CommandHandlerCase[Command, Event, State](
      commandPredicate = predicate,
      statePredicate = state ⇒ statePredicate.test(state.asInstanceOf[S]),
      handler.asInstanceOf[BiFunction[State, Command, Effect[Event, State]]]) :: cases
  }

  /**
   * Match any command which the given `predicate` returns true for
   */
  def matchCommand(predicate: Predicate[Command], handler: BiFunction[S, Command, Effect[Event, State]]): CommandHandlerBuilder[Command, Event, S, State] = {
    addCase(cmd ⇒ predicate.test(cmd), handler)
    this
  }

  /**
   * Match any command which the given `predicate` returns true for.
   *
   * Use this when then `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   */
  def matchCommand(predicate: Predicate[Command], handler: JFunction[Command, Effect[Event, State]]): CommandHandlerBuilder[Command, Event, S, State] = {
    addCase(cmd ⇒ predicate.test(cmd), new BiFunction[S, Command, Effect[Event, State]] {
      override def apply(state: S, cmd: Command): Effect[Event, State] = handler(cmd)
    })
    this
  }

  /**
   * Match commands that are of the given `commandClass` or subclass thereof
   */
  def matchCommand[C <: Command](commandClass: Class[C], handler: BiFunction[S, C, Effect[Event, State]]): CommandHandlerBuilder[Command, Event, S, State] = {
    addCase(cmd ⇒ commandClass.isAssignableFrom(cmd.getClass), handler.asInstanceOf[BiFunction[S, Command, Effect[Event, State]]])
    this
  }

  /**
   * Match commands that are of the given `commandClass` or subclass thereof.
   *
   * Use this when then `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   */
  def matchCommand[C <: Command](commandClass: Class[C], handler: JFunction[C, Effect[Event, State]]): CommandHandlerBuilder[Command, Event, S, State] = {
    matchCommand[C](commandClass, new BiFunction[S, C, Effect[Event, State]] {
      override def apply(state: S, cmd: C): Effect[Event, State] = handler(cmd)
    })
  }

  /**
   * Compose this builder with another builder. The handlers in this builder will be tried first followed
   * by the handlers in `other`.
   */
  def orElse[S2 <: State](other: CommandHandlerBuilder[Command, Event, S2, State]): CommandHandlerBuilder[Command, Event, S2, State] = {
    val newBuilder = new CommandHandlerBuilder[Command, Event, S2, State](other.statePredicate)
    // problem with overloaded constructor with `cases` as parameter
    newBuilder.cases = other.cases ::: cases
    newBuilder
  }

  /**
   * Builds a Command Handler and resets this builder
   */
  def build(): CommandHandler[Command, Event, State] = {
    val builtCases = cases.reverse.toArray
    cases = Nil
    new CommandHandler[Command, Event, State] {
      override def apply(state: State, command: Command): Effect[Event, State] = {
        var idx = 0
        var effect: OptionVal[Effect[Event, State]] = OptionVal.None

        while (idx < builtCases.length && effect.isEmpty) {
          val curr = builtCases(idx)
          if (curr.statePredicate(state) && curr.commandPredicate(command)) {
            val x: Effect[Event, State] = curr.handler.apply(state, command)
            effect = OptionVal.Some(x)
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

