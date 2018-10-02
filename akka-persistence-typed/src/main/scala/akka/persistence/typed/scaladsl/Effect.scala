/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.japi.function
import akka.annotation.DoNotInherit
import akka.persistence.typed.{ SideEffect, Stop }
import akka.persistence.typed.internal._

import scala.collection.{ immutable ⇒ im }

/**
 * Factories for effects - how a persistent actor reacts on a command
 */
object Effect {

  /**
   * Persist a single event
   *
   * Side effects can be chained with `andThen`
   */
  def persist[Event, State](event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist multiple events
   *
   * Side effects can be chained with `andThen`
   */
  def persist[Event, A <: Event, B <: Event, State](evt1: A, evt2: B, events: Event*): Effect[Event, State] =
    persist(evt1 :: evt2 :: events.toList)

  /**
   * Persist multiple events
   *
   * Side effects can be chained with `andThen`
   */
  def persist[Event, State](events: im.Seq[Event]): Effect[Event, State] =
    PersistAll(events)

  /**
   * Do not persist anything
   *
   * Side effects can be chained with `andThen`
   */
  def none[Event, State]: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

  /**
   * This command is not handled, but it is not an error that it isn't.
   *
   * Side effects can be chained with `andThen`
   */
  def unhandled[Event, State]: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]

  /**
   * Stop this persistent actor
   * Side effects can be chained with `andThen`
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
  final def thenRun(callback: State ⇒ Unit): Effect[Event, State] =
    CompositeEffect(this, SideEffect(callback))

  /**
   *  Run the given callback after the current Effect
   */
  def andThen(chainedEffect: SideEffect[State]): Effect[Event, State]

  /**
   *  Run the given callbacks sequentially after the current Effect
   */
  final def andThen(chainedEffects: im.Seq[SideEffect[State]]): Effect[Event, State] =
    CompositeEffect(this, chainedEffects)

  /** The side effect is to stop the actor */
  def andThenStop(): Effect[Event, State] = {
    CompositeEffect(this, Stop.asInstanceOf[SideEffect[State]])
  }
}

