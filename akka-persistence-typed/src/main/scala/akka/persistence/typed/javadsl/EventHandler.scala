/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.function.BiFunction

import akka.util.OptionVal

/**
 * FunctionalInterface for reacting on events having been persisted
 *
 * Used with [[EventHandlerBuilder]] to setup the behavior of a [[PersistentBehavior]]
 */
@FunctionalInterface
trait EventHandler[Event, State] {
  def apply(state: State, event: Event): State
}

object EventHandlerBuilder {
  def builder[Event, State >: Null](): EventHandlerBuilder[Event, State] =
    new EventHandlerBuilder[Event, State]()
}

final class EventHandlerBuilder[Event, State >: Null]() {

  private final case class EventHandlerCase(predicate: Event ⇒ Boolean, handler: BiFunction[State, Event, State])

  private var cases: List[EventHandlerCase] = Nil

  private def addCase(predicate: Event ⇒ Boolean, handler: BiFunction[State, Event, State]): Unit = {
    cases = EventHandlerCase(predicate, handler) :: cases
  }

  /**
   * Match any event which is an instance of `E` or a subtype of `E`
   */
  def matchEvent[E <: Event](eventClass: Class[E], biFunction: BiFunction[State, E, State]): EventHandlerBuilder[Event, State] = {
    addCase(e ⇒ eventClass.isAssignableFrom(e.getClass), biFunction.asInstanceOf[BiFunction[State, Event, State]])
    this
  }

  /**
   * Match any event
   *
   * Builds and returns the handler since this will not let through any states to subsequent match statements
   */
  def matchAny(biFunction: BiFunction[State, Event, State]): EventHandler[Event, State] = {
    addCase(_ ⇒ true, biFunction.asInstanceOf[BiFunction[State, Event, State]])
    build()
  }

  /**
   * Builds and returns a handler from the appended states. The returned [[EventHandler]] will throw a [[scala.MatchError]]
   * if applied to an event that has no defined case.
   *
   * The builder is reset to empty after build has been called.
   */
  def build(): EventHandler[Event, State] = {
    val builtCases = cases.reverse.toArray

    new EventHandler[Event, State] {
      def apply(state: State, event: Event): State = {
        var result: OptionVal[State] = OptionVal.None
        var idx = 0
        while (idx < builtCases.length && result.isEmpty) {
          val curr = builtCases(idx)
          if (curr.predicate(event)) {
            result = OptionVal.Some[State](curr.handler.apply(state, event))
          }
          idx += 1
        }

        result match {
          case OptionVal.None    ⇒ throw new MatchError(s"No match found for event [${event.getClass}] and state [${state.getClass}]")
          case OptionVal.Some(s) ⇒ s
        }
      }
    }
  }
}
