/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.function.BiFunction
import java.util.function.{ Function ⇒ JFunction }

import akka.annotation.InternalApi
import akka.util.OptionVal

/**
 * FunctionalInterface for reacting on events having been persisted
 *
 * Used with [[EventHandlerBuilder]] to setup the behavior of a [[EventSourcedBehavior]]
 */
@FunctionalInterface
trait EventHandler[State, Event] {
  def apply(state: State, event: Event): State
}

object EventHandlerBuilder {
  def builder[State >: Null, Event](): EventHandlerBuilder[State, Event] =
    new EventHandlerBuilder[State, Event]()

  /**
   * INTERNAL API
   */
  @InternalApi private final case class EventHandlerCase[State, Event](
    statePredicate: State ⇒ Boolean,
    eventPredicate: Event ⇒ Boolean,
    handler:        BiFunction[State, Event, State])
}

final class EventHandlerBuilder[State >: Null, Event]() {
  import EventHandlerBuilder.EventHandlerCase

  private var cases: List[EventHandlerCase[State, Event]] = Nil

  private def addCase(eventPredicate: Event ⇒ Boolean, handler: BiFunction[State, Event, State]): Unit = {
    cases = EventHandlerCase[State, Event](_ ⇒ true, eventPredicate, handler) :: cases
  }

  /**
   * Match any event which is an instance of `E` or a subtype of `E`
   */
  def matchEvent[E <: Event](eventClass: Class[E], biFunction: BiFunction[State, E, State]): EventHandlerBuilder[State, Event] = {
    addCase(e ⇒ eventClass.isAssignableFrom(e.getClass), biFunction.asInstanceOf[BiFunction[State, Event, State]])
    this
  }

  /**
   * Match any event which is an instance of `E` or a subtype of `E`.
   *
   * Use this when then `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   */
  def matchEvent[E <: Event](eventClass: Class[E], f: JFunction[E, State]): EventHandlerBuilder[State, Event] = {
    matchEvent[E](eventClass, new BiFunction[State, E, State] {
      override def apply(state: State, event: E): State = f(event)
    })
  }

  def matchEvent[E <: Event, S <: State](eventClass: Class[E], stateClass: Class[S],
                                         biFunction: BiFunction[S, E, State]): EventHandlerBuilder[State, Event] = {

    cases = EventHandlerCase[State, Event](
      statePredicate = s ⇒ s != null && stateClass.isAssignableFrom(s.getClass),
      eventPredicate = e ⇒ eventClass.isAssignableFrom(e.getClass),
      biFunction.asInstanceOf[BiFunction[State, Event, State]]) :: cases
    this
  }

  /**
   * Match any event
   *
   * Builds and returns the handler since this will not let through any states to subsequent match statements
   */
  def matchAny(biFunction: BiFunction[State, Event, State]): EventHandler[State, Event] = {
    addCase(_ ⇒ true, biFunction.asInstanceOf[BiFunction[State, Event, State]])
    build()
  }

  /**
   * Match any event.
   *
   * Use this when then `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   */
  def matchAny(f: JFunction[Event, State]): EventHandler[State, Event] = {
    matchAny(new BiFunction[State, Event, State] {
      override def apply(state: State, event: Event): State = f(event)
    })
    build()
  }

  /**
   * Compose this builder with another builder. The handlers in this builder will be tried first followed
   * by the handlers in `other`.
   */
  def orElse(other: EventHandlerBuilder[State, Event]): EventHandlerBuilder[State, Event] = {
    val newBuilder = new EventHandlerBuilder[State, Event]
    // problem with overloaded constructor with `cases` as parameter
    newBuilder.cases = other.cases ::: cases
    newBuilder
  }

  /**
   * Builds and returns a handler from the appended states. The returned [[EventHandler]] will throw a [[scala.MatchError]]
   * if applied to an event that has no defined case.
   *
   * The builder is reset to empty after build has been called.
   */
  def build(): EventHandler[State, Event] = {
    val builtCases = cases.reverse.toArray

    new EventHandler[State, Event] {
      def apply(state: State, event: Event): State = {
        var result: OptionVal[State] = OptionVal.None
        var idx = 0
        while (idx < builtCases.length && result.isEmpty) {
          val curr = builtCases(idx)
          if (curr.statePredicate(state) && curr.eventPredicate(event)) {
            result = OptionVal.Some[State](curr.handler.apply(state, event))
          }
          idx += 1
        }

        result match {
          case OptionVal.None ⇒
            val stateClass = if (state == null) "null" else state.getClass.getName
            throw new MatchError(s"No match found for event [${event.getClass}] and state [$stateClass]. Has this event been stored using an EventAdapter?")
          case OptionVal.Some(s) ⇒ s
        }
      }
    }
  }
}
