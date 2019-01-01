/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.Objects
import java.util.function.{ BiFunction, Predicate, Supplier, Function ⇒ JFunction }

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

}

final class EventHandlerBuilder[State >: Null, Event]() {

  private var builders: List[EventHandlerBuilderByState[State, State, Event]] = Nil

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   *
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def forState(statePredicate: Predicate[State]): EventHandlerBuilderByState[State, State, Event] = {
    val builder = EventHandlerBuilderByState.builder[State, Event](statePredicate)
    builders = builder :: builders
    builder
  }

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   *
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def forState[S <: State](stateClass: Class[S], statePredicate: Predicate[S]): EventHandlerBuilderByState[S, State, Event] = {
    val builder = new EventHandlerBuilderByState[S, State, Event](stateClass, statePredicate)
    builders = builder.asInstanceOf[EventHandlerBuilderByState[State, State, Event]] :: builders
    builder
  }

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   *
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def forStateType[S <: State](stateClass: Class[S]): EventHandlerBuilderByState[S, State, Event] = {
    val builder = EventHandlerBuilderByState.builder[S, State, Event](stateClass)
    builders = builder.asInstanceOf[EventHandlerBuilderByState[State, State, Event]] :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used when the state is `null`.
   *
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def forNullState(): EventHandlerBuilderByState[State, State, Event] = {
    val builder = EventHandlerBuilderByState.builder[State, Event](s ⇒ Objects.isNull(s))
    builders = builder :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used for any not `null` state.
   *
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def forNonNullState(): EventHandlerBuilderByState[State, State, Event] = {
    val builder = EventHandlerBuilderByState.builder[State, Event](s ⇒ Objects.nonNull(s))
    builders = builder :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used for any  state.
   *
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def forAnyState(): EventHandlerBuilderByState[State, State, Event] = {
    val builder = EventHandlerBuilderByState.builder[State, Event](s ⇒ true)
    builders = builder :: builders
    builder
  }

  def build(): EventHandler[State, Event] = {

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

object EventHandlerBuilderByState {

  private val _trueStatePredicate: Predicate[Any] = new Predicate[Any] {
    override def test(t: Any): Boolean = true
  }

  private def trueStatePredicate[S]: Predicate[S] = _trueStatePredicate.asInstanceOf[Predicate[S]]

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def builder[S <: State, State >: Null, Event](stateClass: Class[S]): EventHandlerBuilderByState[S, State, Event] =
    new EventHandlerBuilderByState(stateClass, statePredicate = trueStatePredicate)

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   * @return A new, mutable, EventHandlerBuilderByState
   */
  def builder[State >: Null, Event](statePredicate: Predicate[State]): EventHandlerBuilderByState[State, State, Event] =
    new EventHandlerBuilderByState(classOf[Any].asInstanceOf[Class[State]], statePredicate)

  /**
   * INTERNAL API
   */
  @InternalApi private final case class EventHandlerCase[State, Event](
    statePredicate: State ⇒ Boolean,
    eventPredicate: Event ⇒ Boolean,
    handler:        BiFunction[State, Event, State])
}

final class EventHandlerBuilderByState[S <: State, State >: Null, Event](val stateClass: Class[S], val statePredicate: Predicate[S]) {

  import EventHandlerBuilderByState.EventHandlerCase

  private var cases: List[EventHandlerCase[State, Event]] = Nil

  private def addCase(eventPredicate: Event ⇒ Boolean, handler: BiFunction[State, Event, State]): Unit = {
    cases = EventHandlerCase[State, Event](_ ⇒ true, eventPredicate, handler) :: cases
  }

  /**
   * Match any event which is an instance of `E` or a subtype of `E`
   */
  def matchEvent[E <: Event](eventClass: Class[E], biFunction: BiFunction[S, E, State]): EventHandlerBuilderByState[S, State, Event] = {
    addCase(e ⇒ eventClass.isAssignableFrom(e.getClass), biFunction.asInstanceOf[BiFunction[State, Event, State]])
    this
  }

  /**
   * Match any event which is an instance of `E` or a subtype of `E`.
   *
   * Use this when then `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   */
  def matchEvent[E <: Event](eventClass: Class[E], f: JFunction[E, State]): EventHandlerBuilderByState[S, State, Event] = {
    matchEvent[E](eventClass, new BiFunction[S, E, State] {
      override def apply(state: S, event: E): State = f(event)
    })
  }

  def matchEvent[E <: Event](eventClass: Class[E], stateClass: Class[S],
                             biFunction: BiFunction[S, E, State]): EventHandlerBuilderByState[S, State, Event] = {

    cases = EventHandlerCase[State, Event](
      statePredicate = s ⇒ s != null && stateClass.isAssignableFrom(s.getClass),
      eventPredicate = e ⇒ eventClass.isAssignableFrom(e.getClass),
      biFunction.asInstanceOf[BiFunction[State, Event, State]]) :: cases
    this
  }

  def matchEvent[E <: Event](eventClass: Class[E], supplier: Supplier[State]): EventHandlerBuilderByState[S, State, Event] = {

    val supplierBiFunction = new BiFunction[S, E, State] {
      def apply(t: S, u: E): State = supplier.get()
    }

    matchEvent(eventClass, supplierBiFunction)
  }

  def matchEvent[E <: Event](eventClass: Class[E], stateClass: Class[S],
                             supplier: Supplier[S]): EventHandlerBuilderByState[S, State, Event] = {

    val supplierBiFunction = new BiFunction[S, E, State] {
      def apply(t: S, u: E): S = supplier.get()
    }

    matchEvent(eventClass, stateClass, supplierBiFunction)
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
  def orElse[S2 <: State](other: EventHandlerBuilderByState[S2, State, Event]): EventHandlerBuilderByState[S2, State, Event] = {
    val newBuilder = new EventHandlerBuilderByState[S2, State, Event](other.stateClass, other.statePredicate)
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
