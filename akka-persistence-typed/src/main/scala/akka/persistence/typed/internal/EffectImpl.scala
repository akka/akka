/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.internal

import akka.persistence.typed.{ javadsl ⇒ j }

import scala.collection.{ immutable ⇒ im }
import akka.annotation.InternalApi
import akka.persistence.typed.scaladsl.PersistentBehaviors.{ ChainableEffect, Effect }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] abstract class EffectImpl[+Event, State] extends j.Effect[Event, State] with Effect[Event, State] {
  /* All events that will be persisted in this effect */
  override def events: im.Seq[Event] = Nil

  /* All side effects that will be performed in this effect */
  override def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = Nil
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object CompositeEffect {
  def apply[Event, State](effect: EffectImpl[Event, State], sideEffects: ChainableEffect[Event, State]): EffectImpl[Event, State] = {
    CompositeEffect[Event, State](
      effect,
      sideEffects :: Nil
    )
  }
}

@InternalApi
private[akka] final case class CompositeEffect[Event, State](
  persistingEffect: EffectImpl[Event, State],
  _sideEffects:     im.Seq[ChainableEffect[Event, State]]) extends EffectImpl[Event, State] {

  override val events = persistingEffect.events

  override def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = _sideEffects.asInstanceOf[im.Seq[ChainableEffect[E, State]]]

}

@InternalApi
private[akka] case object PersistNothing extends EffectImpl[Nothing, Nothing]

@InternalApi
private[akka] case class Persist[Event, State](event: Event) extends EffectImpl[Event, State] {
  override def events = event :: Nil
}

@InternalApi
private[akka] case class PersistAll[Event, State](override val events: im.Seq[Event]) extends EffectImpl[Event, State]

@InternalApi
private[akka] case class SideEffect[Event, State](effect: State ⇒ Unit) extends ChainableEffect[Event, State]

@InternalApi
private[akka] case object Stop extends ChainableEffect[Nothing, Nothing]

@InternalApi
private[akka] case object Unhandled extends EffectImpl[Nothing, Nothing]

