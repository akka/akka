/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.javadsl

import akka.actor.typed.javadsl.ActorContext
import akka.annotation.InternalApi
import akka.japi.pf.FI
import akka.persistence.typed.scaladsl.PersistentBehaviors.EffectImpl
import akka.util.OptionVal

/**
 * SAM for reacting on commands
 *
 * Used with [[CommandHandlerBuilder]] to setup the behavior of a [[PersistentBehavior]]
 */
@FunctionalInterface
trait CommandHandler[Command, Event, State] {
  def apply(ctx: ActorContext[Command], state: State, command: Command): Effect[Event, State]
}
/**
 * SAM for reacting on signals
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

object CommandHandlerBuilder {
  def builder[Command, Event, State](rootCommandClass: Class[Command]): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State](rootCommandClass)
}

final class CommandHandlerBuilder[Command, Event, State] @InternalApi private[persistence] (rootCommandClass: Class[Command]) {

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

