/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.annotation.DoNotInherit
import akka.persistence.typed.internal._

import scala.collection.{ immutable ⇒ im }

/**
 * Factories for effects - how a persistent actor reacts on a command
 */
object Effect {

  // TODO docs
  def persist[Event, State](event: Event): Effect[Event, State] = Persist(event)

  // TODO docs
  def persist[Event, A <: Event, B <: Event, State](evt1: A, evt2: B, events: Event*): Effect[Event, State] =
    persist(evt1 :: evt2 :: events.toList)

  // TODO docs
  def persist[Event, State](eventOpt: Option[Event]): Effect[Event, State] =
    eventOpt match {
      case Some(evt) ⇒ persist[Event, State](evt)
      case _         ⇒ none[Event, State]
    }

  // TODO docs
  def persist[Event, State](events: im.Seq[Event]): Effect[Event, State] =
    PersistAll(events)

  // TODO docs
  def persist[Event, State](events: im.Seq[Event], sideEffects: im.Seq[ChainableEffect[Event, State]]): Effect[Event, State] =
    new CompositeEffect[Event, State](PersistAll[Event, State](events), sideEffects)

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
  def stop[Event, State]: ChainableEffect[Event, State] = Stop.asInstanceOf[ChainableEffect[Event, State]]
}

/**
 * Instances are created through the factories in the [[Effect]] companion object.
 *
 * Not for user extension.
 */
@DoNotInherit
trait Effect[+Event, State] extends akka.persistence.typed.javadsl.Effect[Event, State] { self: EffectImpl[Event, State] ⇒
  /* All events that will be persisted in this effect */
  def events: im.Seq[Event]

  def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]]

  /** Convenience method to register a side effect with just a callback function */
  final def andThen(callback: State ⇒ Unit): Effect[Event, State] =
    CompositeEffect(this, SideEffect[Event, State](callback))

  /** Convenience method to register a side effect with just a lazy expression */
  final def andThen(callback: ⇒ Unit): Effect[Event, State] =
    CompositeEffect(this, SideEffect[Event, State]((_: State) ⇒ callback))

  /** The side effect is to stop the actor */
  def andThenStop: Effect[Event, State] =
    CompositeEffect(this, Effect.stop[Event, State])
}
