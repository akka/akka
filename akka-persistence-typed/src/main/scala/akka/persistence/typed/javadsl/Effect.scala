/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import akka.annotation.DoNotInherit
import akka.japi.function
import akka.persistence.typed.internal._

import scala.collection.JavaConverters._

object EffectFactory extends EffectFactories[Nothing, Nothing, Nothing]

@DoNotInherit sealed class EffectFactories[Command, Event, State] {
  /**
   * Persist a single event
   */
  final def persist(event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist all of a the given events. Each event will be applied through `applyEffect` separately but not until
   * all events has been persisted. If an `afterCallBack` is added through [[Effect#andThen]] that will invoked
   * after all the events has been persisted.
   */
  final def persist(events: java.util.List[Event]): Effect[Event, State] = PersistAll(events.asScala.toVector)

  /**
   * Do not persist anything
   */
  def none: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

  /**
   * Stop this persistent actor
   */
  def stop: Effect[Event, State] = Stop.asInstanceOf[ChainableEffect[Event, State]]

  /**
   * This command is not handled, but it is not an error that it isn't.
   */
  def unhandled: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]
}

/**
 * A command handler returns an `Effect` directive that defines what event or events to persist.
 *
 * Additional side effects can be performed in the callback `andThen`
 *
 * Instances of `Effect` are available through factories in the respective Java and Scala DSL packages.
 *
 * Not intended for user extension.
 */
@DoNotInherit abstract class Effect[+Event, State] {
  self: EffectImpl[Event, State] ⇒
  /** Convenience method to register a side effect with just a callback function */
  final def andThen(callback: function.Procedure[State]): Effect[Event, State] =
    CompositeEffect(this, SideEffect[Event, State](s ⇒ callback.apply(s)))

  /** Convenience method to register a side effect that doesn't need access to state */
  final def andThen(callback: function.Effect): Effect[Event, State] =
    CompositeEffect(this, SideEffect[Event, State]((_: State) ⇒ callback.apply()))
}
