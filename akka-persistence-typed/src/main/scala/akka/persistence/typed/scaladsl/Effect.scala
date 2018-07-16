/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.japi.function
import akka.annotation.DoNotInherit
import akka.persistence.typed.internal._

import scala.collection.{ immutable ⇒ im }

/**
 * Factories for effects - how a persistent actor reacts on a command
 */
object Effect {

  /**
   * Persist a single event.
   */
  def persist[Event, State](event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist multiple events
   */
  def persist[Event, A <: Event, B <: Event, State](evt1: A, evt2: B, events: Event*): Effect[Event, State] =
    persist(evt1 :: evt2 :: events.toList)

  /**
   * Persist multiple events
   */
  def persist[Event, State](events: im.Seq[Event]): Effect[Event, State] =
    PersistAll(events)

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
  def stop[Event, State]: Effect[Event, State] = none.andThenStop()
}

/**
 * Instances are created through the factories in the [[Effect]] companion object.
 *
 * Not for user extension.
 */
@DoNotInherit
trait Effect[+Event, State] {
  /* All events that will be persisted in this effect */
  def events: im.Seq[Event]

  /**
   * Run the given callback. Callbacks are run sequentially.
   */
  final def andThen(callback: State ⇒ Unit): Effect[Event, State] =
    CompositeEffect(this, ChainedEffect(callback))

  // not named andThen so can pass a pattern match to andThen

  /**
   *  Run the given callback after the current Effect
   */
  final def andChain(chainedEffect: ChainedEffect[State]): Effect[Event, State] =
    CompositeEffect(this, chainedEffect)

  /**
   *  Run the given callbacks sequentially after the current Effect
   */
  final def andChain(chainedEffects: im.Seq[ChainedEffect[State]]): Effect[Event, State] =
    CompositeEffect(this, chainedEffects)

  /** The side effect is to stop the actor */
  def andThenStop(): Effect[Event, State] = {
    CompositeEffect(this, Stop.asInstanceOf[ChainedEffect[State]])
  }
}

/**
 * A ChainedEffect is an side effect that can be chained after a main effect.
 *
 * Persist, none and unhandled are main effects. Then any number of
 * call backs can be added to these effects with `andThen`.
 *
 * Not for user extension
 */
@DoNotInherit
abstract class ChainedEffect[State]

object ChainedEffect {
  /**
   * Create a ChainedEffect that can be run after Effects
   */
  def apply[State](callback: State ⇒ Unit): ChainedEffect[State] =
    SideEffect(callback)

  /**
   * Java API
   *
   * Create a ChainedEffect that can be run after Effects
   */
  def create[State](callback: function.Procedure[State]): ChainedEffect[State] =
    SideEffect(s ⇒ callback.apply(s))

  def stop[State](): ChainedEffect[State] = Stop.asInstanceOf[ChainedEffect[State]]
}

