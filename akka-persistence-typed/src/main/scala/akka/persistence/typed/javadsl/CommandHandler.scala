/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.Objects
import java.util.function.{ BiFunction, Predicate, Supplier, Function ⇒ JFunction }

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
  def builder[Command, Event, State](): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State]
}

final class CommandHandlerBuilder[Command, Event, State]() {

  private var builders: List[CommandHandlerBuilderByState[Command, Event, State, State]] = Nil

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   *
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def forState(statePredicate: Predicate[State]): CommandHandlerBuilderByState[Command, Event, State, State] = {
    val builder = CommandHandlerBuilderByState.builder[Command, Event, State](statePredicate)
    builders = builder :: builders
    builder
  }

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   *
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def forState[S <: State](stateClass: Class[S], statePredicate: Predicate[S]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    val builder = new CommandHandlerBuilderByState[Command, Event, S, State](stateClass, statePredicate)
    builders = builder.asInstanceOf[CommandHandlerBuilderByState[Command, Event, State, State]] :: builders
    builder
  }

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   *
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def forStateType[S <: State](stateClass: Class[S]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    val builder = CommandHandlerBuilderByState.builder[Command, Event, S, State](stateClass)
    builders = builder.asInstanceOf[CommandHandlerBuilderByState[Command, Event, State, State]] :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used when the state is `null`.
   *
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def forNullState(): CommandHandlerBuilderByState[Command, Event, State, State] = {
    val builder = CommandHandlerBuilderByState.builder[Command, Event, State](s ⇒ Objects.isNull(s))
    builders = builder :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used for any not `null` state.
   *
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def forNonNullState(): CommandHandlerBuilderByState[Command, Event, State, State] = {
    val builder = CommandHandlerBuilderByState.builder[Command, Event, State](s ⇒ Objects.nonNull(s))
    builders = builder :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used for any state.
   *
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def forAnyState(): CommandHandlerBuilderByState[Command, Event, State, State] = {
    val builder = CommandHandlerBuilderByState.builder[Command, Event, State](_ ⇒ true)
    builders = builder :: builders
    builder
  }

  def build(): CommandHandler[Command, Event, State] = {

    val combined =
      builders.reverse match {
        case head :: Nil ⇒ head
        case head :: tail ⇒ tail.foldLeft(head) { (acc, builder) ⇒
          acc.orElse(builder)
        }
        case Nil ⇒ throw new IllegalStateException("No matchers defined")
      }

    combined.build()
  }

}

object CommandHandlerBuilderByState {

  private val _trueStatePredicate: Predicate[Any] = new Predicate[Any] {
    override def test(t: Any): Boolean = true
  }

  private def trueStatePredicate[S]: Predicate[S] = _trueStatePredicate.asInstanceOf[Predicate[S]]

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def builder[Command, Event, S <: State, State](stateClass: Class[S]): CommandHandlerBuilderByState[Command, Event, S, State] =
    new CommandHandlerBuilderByState(stateClass, statePredicate = trueStatePredicate)

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   * @return A new, mutable, CommandHandlerBuilderByState
   */
  def builder[Command, Event, State](statePredicate: Predicate[State]): CommandHandlerBuilderByState[Command, Event, State, State] =
    new CommandHandlerBuilderByState(classOf[Any].asInstanceOf[Class[State]], statePredicate)

  /**
   * INTERNAL API
   */
  @InternalApi private final case class CommandHandlerCase[Command, Event, State](
    commandPredicate: Command ⇒ Boolean,
    statePredicate:   State ⇒ Boolean,
    handler:          BiFunction[State, Command, Effect[Event, State]])
}

final class CommandHandlerBuilderByState[Command, Event, S <: State, State] @InternalApi private[persistence] (
  val stateClass: Class[S], val statePredicate: Predicate[S]) {

  import CommandHandlerBuilderByState.CommandHandlerCase

  private var cases: List[CommandHandlerCase[Command, Event, State]] = Nil

  private def addCase(predicate: Command ⇒ Boolean, handler: BiFunction[S, Command, Effect[Event, State]]): Unit = {
    cases = CommandHandlerCase[Command, Event, State](
      commandPredicate = predicate,
      statePredicate = state ⇒ stateClass.isAssignableFrom(state.getClass) && statePredicate.test(state.asInstanceOf[S]),
      handler.asInstanceOf[BiFunction[State, Command, Effect[Event, State]]]) :: cases
  }

  /**
   * Match any command which the given `predicate` returns true for
   */
  def matchCommand(predicate: Predicate[Command], handler: BiFunction[S, Command, Effect[Event, State]]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    addCase(cmd ⇒ predicate.test(cmd), handler)
    this
  }

  /**
   * Match any command which the given `predicate` returns true for
   */
  def matchCommand(predicate: Predicate[Command], handler: Function[Command, Effect[Event, State]]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    addCase(cmd ⇒ predicate.test(cmd), (_, cmd) ⇒ handler.asInstanceOf[Function[Command, Effect[Event, State]]].apply(cmd))
    this
  }

  def matchCommand[C <: Command](commandClass: Class[C], handler: BiFunction[S, C, Effect[Event, State]]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    addCase(cmd ⇒ commandClass.isAssignableFrom(cmd.getClass), handler.asInstanceOf[BiFunction[S, Command, Effect[Event, State]]])
    this
  }

  def matchCommand[C <: Command](commandClass: Class[C], handler: Function[C, Effect[Event, State]]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    addCase(cmd ⇒ commandClass.isAssignableFrom(cmd.getClass), (_, cmd) ⇒ handler.asInstanceOf[Function[Command, Effect[Event, State]]].apply(cmd))
    this
  }

  def matchCommand[C <: Command](commandClass: Class[C], handler: Supplier[Effect[Event, State]]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    addCase(cmd ⇒ commandClass.isAssignableFrom(cmd.getClass), (_, _) ⇒ handler.get())
    this
  }

  def matchAny(handler: Supplier[Effect[Event, State]]): CommandHandlerBuilderByState[Command, Event, S, State] = {
    addCase(_ ⇒ true, (_, _) ⇒ handler.get())
    this
  }

  /**
   * Compose this builder with another builder. The handlers in this builder will be tried first followed
   * by the handlers in `other`.
   */
  def orElse[S2 <: State](other: CommandHandlerBuilderByState[Command, Event, S2, State]): CommandHandlerBuilderByState[Command, Event, S2, State] = {
    val newBuilder = new CommandHandlerBuilderByState[Command, Event, S2, State](other.stateClass, other.statePredicate)
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

